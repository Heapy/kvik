#!/usr/bin/env python3
"""Fast actual-process recomposition ownership probes using the real bootstrap guardian.
The sleeping bootstrap fixture tests lifecycle, not compiler correctness; the separate
recomposition harness demonstrates real Native compilation and exec restart.
"""
import argparse,atexit,json,os,queue,signal,subprocess,sys,tempfile,threading,time
from pathlib import Path
import jsonschema
ROOT=Path(__file__).resolve().parents[1]

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--java-home',type=Path,required=True);p.add_argument('--konan-home',type=Path,required=True);p.add_argument('--skip-build',action='store_true');args=p.parse_args()
 output=ROOT/'.kvik/tests/foreground-recomposition';output.mkdir(parents=True,exist_ok=True)
 env=dict(os.environ,JAVA_HOME=str(args.java_home));native=ROOT/'execution/platform'
 def run(argv):subprocess.run(list(map(str,argv)),env=env,check=True)
 if not args.skip_build:
  flags=['/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang','-isysroot','/Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk','-std=c11','-D_DARWIN_C_SOURCE','-Wall','-Wextra','-Werror']
  run([*flags,native/'guardian.c','-o',output/'guardian']);run([*flags,'-c',native/'process.c','-o',output/'process.o']);run(['ar','rcs',output/'libkvik-process.a',output/'process.o'])
  run([args.konan_home/'bin/cinterop','-def',native/'process.def','-compiler-option',f'-I{native}','-libraryPath',output,'-target','macos_arm64','-o',output/'process'])
  sources=[f for d in ('core/src','execution/src','integration/foreground/src') for f in sorted((ROOT/d).rglob('*.kt'))]
  run([args.konan_home/'bin/konanc','-target','macos_arm64','-Xoverride-konan-properties=airplaneMode=true','-library',output/'process.klib',*sources,ROOT/'cache/src/kvik/cache/NativeFiles.kt',ROOT/'tests/foreground-recomposition/RecompositionProbe.kt','-entry','kvik.foreground.recompositionprobe.main','-o',output/'probe.kexe'])
 evidence=Path(tempfile.mkdtemp(prefix='lifecycle-',dir=output)).resolve();schema=json.loads((ROOT/'schemas/v1/session.json').read_text());validator=jsonschema.Draft202012Validator({**schema,'$ref':'#/$defs/event'})
 processes=[]
 def cleanup():
  for proc in processes:
   if proc.poll() is None:proc.kill();proc.wait(timeout=10)
 atexit.register(cleanup)
 def tree(pid):
  rows=[line.split(None,2) for line in subprocess.run(['ps','-axo','pid=,ppid=,command='],capture_output=True,text=True,check=True).stdout.splitlines()];found={pid}
  while True:
   more=found|{int(r[0]) for r in rows if int(r[1]) in found}
   if more==found:return {int(r[0]):r[2] for r in rows if int(r[0]) in found}
   found=more
 def alive(pid):
  result=subprocess.run(['ps','-p',str(pid),'-o','stat='],capture_output=True,text=True);return result.returncode==0 and not result.stdout.strip().startswith('Z')
 summaries=[]
 class Session:
  def __init__(self,label,phase,parent=False,unread=False):
   self.path=evidence/label;self.path.mkdir();self.events=[];self.queue=queue.Queue()
   launcher=self.path/'kvik';worker=self.path/'worker.py'
   descriptor=dict(schemaVersion=1,sourceRoot=str(self.path),python=sys.executable,launcher=str(launcher),bootstrapArguments=['--konan-data',str(self.path/'data'),'--cache-dir',str(self.path/'cache'),'--artifact-cache',str(self.path/'artifacts'),'--offline'],composition=dict(hash='a'*64,sizeBytes=42),runtime=str(self.path/'cache/executables'/('a'*64)/'kvik.kexe'))
   (self.path/'descriptor.json').write_text(json.dumps(descriptor));(self.path/'phase').write_text(phase)
   launcher.write_text('import json,pathlib,sys,os\n'+'sys.path.insert(0,'+repr(str(ROOT))+')\nfrom bootstrap.launcher import run_owned\np=pathlib.Path(__file__).parent\n'+'''mode=(p/'phase').read_text()
if '--print-composition' in sys.argv:
 if mode=='query-slow':
  (p/'entered').write_text('query')
  with (p/'nested.log').open('w') as log:run_owned(['/bin/sleep','120'],{'PATH':'/usr/bin:/bin'},log,120)
 print(json.dumps(dict(schemaVersion=1,composition=dict(hash='b'*64,sizeBytes=42))))
else:
 if mode=='build-barrier':
  import time
  while not (p/'release').exists():time.sleep(.01)
 if mode=='build-slow':
  (p/'entered').write_text('build')
  with (p/'nested.log').open('w') as log:run_owned(['/bin/sleep','120'],{'PATH':'/usr/bin:/bin'},log,120)
 data=json.loads((p/'descriptor.json').read_text());data['composition']['hash']='b'*64;data['runtime']=str(p/'cache/executables'/('b'*64)/'kvik.kexe');print(json.dumps(data))
''')
   worker.write_text('#!'+sys.executable+'\nimport pathlib,sys\np=pathlib.Path.cwd();(p/"CONSUMER_RAN").write_text("unexpected stale work")\n');worker.chmod(0o755)
   command=[str(output/'probe.kexe'),str(output/'guardian'),str(worker),str(self.path),str(self.path/'descriptor.json')]
   if parent:command=[sys.executable,'-c','import pathlib,subprocess,sys;p=subprocess.Popen(sys.argv[2:]);pathlib.Path(sys.argv[1]).write_text(str(p.pid));p.wait()',str(self.path/'coordinator.pid'),*command]
   self.err=(self.path/'stderr.log').open('w');self.log=(self.path/'events.jsonl').open('w');self.proc=subprocess.Popen(command,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=self.err,text=True,bufsize=1);processes.append(self.proc)
   def reader():
    try:
     for line in self.proc.stdout:self.log.write(line);self.log.flush();self.queue.put(json.loads(line))
    finally:self.queue.put(None)
   self.thread=threading.Thread(target=reader,daemon=True)
   if not unread:self.thread.start();self.wait('session-started')
  def wait(self,kind,predicate=lambda e:True):
   deadline=time.monotonic()+15
   while True:
    event=self.queue.get(timeout=max(.01,deadline-time.monotonic()));assert event is not None,(kind,self.events,self.proc.poll());validator.validate(event);self.events.append(event)
    if event['type']==kind and predicate(event):return event
  def raw(self,value):self.proc.stdin.write(value);self.proc.stdin.flush()
  def send(self,cmd):self.raw(json.dumps(dict(schemaVersion=1,requestId=cmd,command=cmd))+'\n')
  def finish(self,reason):
   owned=tree(self.proc.pid);started=time.monotonic()
   if reason=='q':self.send('q')
   elif reason=='eof':self.proc.stdin.close()
   elif reason in ('int','term'):self.proc.send_signal(signal.SIGINT if reason=='int' else signal.SIGTERM)
   elif reason in ('kill','parent'):self.proc.kill()
   self.proc.wait(timeout=10)
   assert self.proc.returncode==(-9 if reason in ('kill','parent') else 130 if reason in ('int','term') else 0)
   self.thread.join(timeout=5);self.log.close();self.err.close()
   if (self.path/'coordinator.pid').exists():owned[int((self.path/'coordinator.pid').read_text())]='coordinator'
   for pid in owned:
    deadline=time.monotonic()+5
    while alive(pid) and time.monotonic()<deadline:time.sleep(.02)
    assert not alive(pid),(reason,pid,owned[pid])
   assert not (self.path/'CONSUMER_RAN').exists(),'stale consumer work ran'
   elapsed=time.monotonic()-started;assert elapsed<3,(reason,elapsed);return elapsed
 for phase in ('query-slow','build-slow'):
  for reason in ('q','eof','int','term','kill','parent'):
   s=Session(phase+'-'+reason,phase,parent=reason=='parent');deadline=time.monotonic()+15
   while time.monotonic()<deadline:
    if any('/bin/sleep 120' in cmd for cmd in tree(s.proc.pid).values()):break
    time.sleep(.03)
   else:raise AssertionError('owned nested bootstrap worker not observed')
   s.send('status');assert not s.wait('control')['payload']['compositionReady']
   elapsed=s.finish(reason);summaries.append(dict(phase=phase,reason=reason,seconds=elapsed));print('PASS',phase,reason,round(elapsed,3),flush=True)
 s=Session('failed-exec','fast');s.wait('diagnostic',lambda e:e['payload']['code']=='KVIK_SESSION_RESTART');s.send('status');assert not s.wait('control')['payload']['compositionReady'];s.finish('q');print('PASS actual exec failure preserves old controls and stale gate',flush=True)
 s=Session('coalesced-quit','build-slow');s.wait('rebuild-started');s.raw(''.join(json.dumps(dict(schemaVersion=1,requestId=c,command=c))+'\n' for c in ('t','q','r')));s.finish('none');assert not any(e['type']=='session-restarting' for e in s.events)
 # Hold the replacement behind a barrier while the exclusive stdout reader stops.
 # Less than the queue limit, but more than the pipe capacity: handoff must time out
 # without invoking exec, even if the last emitted JSONL frame is partial.
 s=Session('handoff-backpressure','build-barrier',unread=True)
 def flood():
  try:s.raw(''.join(json.dumps(dict(schemaVersion=1,requestId=str(i),command='status'))+'\n' for i in range(700)))
  except BrokenPipeError:pass
 writer=threading.Thread(target=flood,daemon=True);writer.start()
 deadline=time.monotonic()+15
 while time.monotonic()<deadline:
  journal=s.path/'session.jsonl'
  lines=journal.read_text().splitlines() if journal.exists() else []
  events=[json.loads(line) for line in lines if line.endswith('}')]
  if sum(e['type']=='control' for e in events)==700 and any(e['type']=='rebuild-started' for e in events):break
  time.sleep(.02)
 else:raise AssertionError('backpressure setup did not reach controls/rebuild barrier')
 owned=tree(s.proc.pid);(s.path/'release').touch();s.proc.wait(timeout=10);assert s.proc.returncode==1
 writer.join(timeout=3);s.log.close();s.err.close()
 stream=s.proc.stdout.read();(s.path/'stream-after-close.jsonl').write_text(stream)
 events=[json.loads(line) for line in (s.path/'session.jsonl').read_text().splitlines()]
 for event in events:validator.validate(event)
 assert sum(e['type']=='session-restarting' for e in events)==1
 assert not any(e['type']=='diagnostic' and e['payload']['code']=='KVIK_SESSION_RESTART' for e in events),'exec crossed blocked output'
 assert events[-1]['type']=='session-finished' and events[-1]['payload']['reason']=='output-backpressure'
 for line in stream.splitlines(keepends=True):
  if line.endswith('\n'):validator.validate(json.loads(line))
 for pid in owned:
  deadline=time.monotonic()+5
  while alive(pid) and time.monotonic()<deadline:time.sleep(.02)
  assert not alive(pid),('backpressure survivor',pid)
 print('PASS handoff drains complete JSONL or closes without exec under backpressure',flush=True)
 (evidence/'summary.json').write_text(json.dumps(summaries,indent=2));print('PASS recomposition lifecycle',evidence,flush=True)
if __name__=='__main__':main()
