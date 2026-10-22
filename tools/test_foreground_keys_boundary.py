#!/usr/bin/env python3
"""Light C/PTY tests for the actual invocation-owned terminal lease. No Native compile."""
import argparse
import atexit
import json
import os
from pathlib import Path
import pty
import queue
import signal
import subprocess
import sys
import tempfile
import threading
import time
import termios

ROOT = Path(__file__).resolve().parents[1]
BROKER = r'''
import os,signal,sys,subprocess,fcntl,termios
os.setsid(); terminal=os.open(sys.argv[1],os.O_RDWR|os.O_NOCTTY)
fcntl.ioctl(terminal,termios.TIOCSCTTY,0);signal.signal(signal.SIGTTOU,signal.SIG_IGN)
if sys.argv[4]=='unsupported':
 value=termios.tcgetattr(terminal);value[3]&=~termios.ICANON;termios.tcsetattr(terminal,termios.TCSANOW,value)
gate,release=os.pipe(); env=dict(os.environ,KVIK_TEST_GATE=str(gate))
if sys.argv[4]=='parent':
 receive,send=os.pipe()
 launch="import os,subprocess,sys;p=subprocess.Popen(sys.argv[3:],pass_fds=(int(sys.argv[2]),),preexec_fn=lambda:os.setpgid(0,0));os.write(int(sys.argv[1]),str(p.pid).encode());os.close(int(sys.argv[1]));os.close(int(sys.argv[2]));p.wait()"
 child=subprocess.Popen([sys.executable,'-c',launch,str(send),str(gate),sys.argv[2],sys.argv[3],'keys'],stdin=terminal,env=env,pass_fds=(gate,send))
 os.close(send);owner=int(os.read(receive,100));os.close(receive)
else:
 child=subprocess.Popen([sys.argv[2],sys.argv[3],'keys'],stdin=terminal,env=env,pass_fds=(gate,),preexec_fn=lambda:os.setpgid(0,0));owner=child.pid
os.close(gate);print('broker',owner,child.pid,flush=True)
os.tcsetpgrp(terminal,owner);os.write(release,b'!');os.close(release)
for command in sys.stdin:
 if command.strip()=='wait':
  print('exit',child.wait(timeout=10),flush=True)
  subprocess.run([sys.argv[2],'inspect'],stdin=terminal,check=True)
 elif command.strip() in ('compete-keys','compete-lines'):
  mode=command.strip().split('-')[1]
  other=subprocess.run([sys.argv[2],sys.argv[3],mode],stdin=terminal,preexec_fn=lambda:os.setpgid(0,child.pid))
  print('competitor',other.returncode,flush=True)
 elif command.strip()=='end':break
if child.poll() is None:child.kill();child.wait(timeout=5)
'''

RACE = r'''
import fcntl,os,signal,subprocess,sys,termios
os.setsid();terminal=os.open(sys.argv[1],os.O_RDWR|os.O_NOCTTY)
fcntl.ioctl(terminal,termios.TIOCSCTTY,0);signal.signal(signal.SIGTTOU,signal.SIG_IGN)
subprocess.run([sys.argv[2],'inspect'],stdin=terminal,check=True)
gate,release=os.pipe();env=dict(os.environ,KVIK_TEST_GATE=str(gate),KVIK_TEST_SKIP_STATE='1')
first=subprocess.Popen([sys.argv[2],sys.argv[3],'keys'],stdin=terminal,env=env,pass_fds=(gate,),preexec_fn=lambda:os.setpgid(0,0))
second=subprocess.Popen([sys.argv[2],sys.argv[3],'keys'],stdin=terminal,env=env,pass_fds=(gate,),preexec_fn=lambda:os.setpgid(0,first.pid))
os.close(gate);os.tcsetpgrp(terminal,first.pid);os.write(release,b'!!');os.close(release)
print('race',first.pid,second.pid,flush=True)
for command in sys.stdin:
 if command.strip()=='wait':
  print('exits',*sorted([first.wait(timeout=10),second.wait(timeout=10)]),flush=True)
  subprocess.run([sys.argv[2],'inspect'],stdin=terminal,check=True)
 elif command.strip()=='end':break
for child in (first,second):
 if child.poll() is None:child.kill();child.wait(timeout=5)
'''


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--skip-build',action='store_true');args=parser.parse_args()
    output=ROOT/'.kvik/tests/foreground-keys';output.mkdir(parents=True,exist_ok=True)
    if not args.skip_build:
        clang='/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang'
        sdk='/Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk'
        flags=[clang,'-isysroot',sdk,'-std=c11','-D_DARWIN_C_SOURCE','-Wall','-Wextra','-Werror']
        subprocess.run([*flags,ROOT/'execution/platform/guardian.c','-o',output/'guardian'],check=True)
        subprocess.run([*flags,'-I'+str(ROOT/'execution/platform'),ROOT/'execution/platform/process.c',ROOT/'tests/foreground-keys/TerminalProbe.c','-o',output/'probe'],check=True)
    evidence=Path(tempfile.mkdtemp(prefix='boundary-',dir=output));sessions=[];results=[]
    def alive(pid):
        status=subprocess.run(['ps','-p',str(pid),'-o','stat='],capture_output=True,text=True)
        return status.returncode==0 and not status.stdout.strip().startswith('Z')
    class Session:
        def __init__(self,label,mode='keys'):
            self.label=label;self.master,self.slave=pty.openpty();self.events=[];self.messages=queue.Queue()
            self.log=(evidence/(label+'.log')).open('w')
            self.proc=subprocess.Popen([sys.executable,'-c',BROKER,os.ttyname(self.slave),str(output/'probe'),str(output/'guardian'),mode],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
            sessions.append(self)
            def reader():
                for line in self.proc.stdout:self.log.write(line);self.log.flush();self.messages.put(line.strip())
                self.messages.put(None)
            self.thread=threading.Thread(target=reader,daemon=True);self.thread.start()
            owner=self.wait('broker').split();self.pid=int(owner[1]);self.parent=int(owner[2]);self.before=self.wait('state')
            self.helper=None
            if mode!='unsupported':self.helper=int(self.wait('ready').split()[2])
        def wait(self,prefix):
            deadline=time.monotonic()+10
            while True:
                line=self.messages.get(timeout=max(.01,deadline-time.monotonic()));assert line is not None,(self.label,prefix,self.events)
                self.events.append(line)
                if line.startswith(prefix):return line
        def command(self,value):self.proc.stdin.write(value+'\n');self.proc.stdin.flush()
        def keys(self,value):os.write(self.master,value)
        def finish(self,reason='q',expected=0,queued=None):
            start=time.monotonic()
            if reason=='q':self.keys(b'q')
            elif reason=='ctrl-d':self.keys(b'\x04')
            elif reason=='ctrl-c':self.keys(b'\x03')
            elif reason=='term':os.kill(self.pid,signal.SIGTERM)
            elif reason=='kill':os.kill(self.pid,signal.SIGKILL);expected=-9
            elif reason=='helper-kill':os.kill(self.helper,signal.SIGKILL);expected=21
            elif reason=='helper-stop':os.kill(self.helper,signal.SIGSTOP);expected=21
            elif reason=='parent':os.kill(self.parent,signal.SIGKILL);expected=-9
            self.command('wait');assert self.wait('exit')=='exit '+str(expected),(self.label,self.events)
            after=self.wait('state')
            if reason=='parent' and after!=self.before:after=self.wait('state')
            # Compare the entire zero-initialized native termios structure and both
            # inherited descriptors' full F_GETFL/F_GETFD after initial output.
            assert after==self.before,(self.label,self.before,after)
            if queued is not None:
                import select
                assert select.select([self.slave],[],[],1)[0], 'restored input disappeared'
                assert os.read(self.slave,4096)==queued, 'restoration consumed queued input'
            for pid in (self.pid,self.helper):
                if not pid:continue
                deadline=time.monotonic()+5
                while alive(pid) and time.monotonic()<deadline:time.sleep(.01)
                assert not alive(pid),('survivor',self.label,pid)
            elapsed=time.monotonic()-start
            self.command('end');os.close(self.master);os.close(self.slave)
            self.proc.wait(timeout=5);self.thread.join(timeout=5);self.log.close()
            results.append(dict(label=self.label,seconds=elapsed,original=self.before,restored=after))
            print('PASS',self.label,round(elapsed,3),flush=True)
    def cleanup():
        for session in sessions:
            if session.proc.poll() is None:
                try:session.command('end')
                except (BrokenPipeError,ValueError):pass
                for fd in (session.master,session.slave):
                    try:os.close(fd)
                    except OSError:pass
                try:session.proc.wait(timeout=3)
                except subprocess.TimeoutExpired:session.proc.kill();session.proc.wait(timeout=3)
    atexit.register(cleanup)
    for reason in ('q','ctrl-d','ctrl-c','term','kill','helper-kill','helper-stop'):
        s=Session(reason);s.keys(b'rt?\x13\x1a')
        for key in (114,116,63,19,26):assert s.wait('key')=='key '+str(key)
        s.finish(reason)
    s=Session('parent-loss','parent');s.finish('parent')
    s=Session('queued-input-after-quit');s.keys(b'qkept\n');s.finish(reason='already-quit',queued=b'kept\n')
    s=Session('failed-and-successful-exec');s.keys(b'f');s.wait('exec-failed-recovered');s.keys(b'e')
    resumed=s.wait('resumed');assert resumed==f'resumed {s.pid} {s.helper}'
    s.finish()
    s=Session('pending-key-across-exec');s.keys(b'eq');assert s.wait('resumed')==f'resumed {s.pid} {s.helper}';s.finish(reason='already-quit')
    for label,key in [('pending-int-across-exec',b'i'),('pending-term-across-exec',b'j')]:
        s=Session(label);s.keys(key);assert s.wait('resumed')==f'resumed {s.pid} {s.helper}';s.finish(reason='already-quit')
    s=Session('invalid-lease-bindings');s.keys(b'v');s.wait('invalid-leases-rejected');s.keys(b'?');assert s.wait('key')=='key 63';s.finish()
    s=Session('exclusive-tty')
    for mode in ('keys','lines'):
        s.command('compete-'+mode);assert s.wait('rejected')=='rejected 35';assert s.wait('competitor')=='competitor 20'
    s.finish()
    first,second=Session('independent-a'),Session('independent-b');first.finish();second.keys(b't');assert second.wait('key')=='key 116';second.finish()
    s=Session('noncanonical-rejected','unsupported');assert s.wait('rejected')=='rejected 45';s.finish(reason='already-exited',expected=20)
    master,slave=pty.openpty()
    race=subprocess.Popen([sys.executable,'-c',RACE,os.ttyname(slave),str(output/'probe'),str(output/'guardian')],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
    race_lines=[]
    try:
        before=race.stdout.readline().strip();race_lines.append(before);assert before.startswith('state ')
        ready=rejected=False;owned=[]
        while not (ready and rejected):
            line=race.stdout.readline().strip();race_lines.append(line)
            if line.startswith('ready '):ready=True;owned.extend(map(int,line.split()[1:]))
            if line.startswith('race '):owned.extend(map(int,line.split()[1:]))
            if line.startswith('rejected '):assert line=='rejected 35';rejected=True
        os.write(master,b'q');race.stdin.write('wait\n');race.stdin.flush()
        while True:
            line=race.stdout.readline().strip();race_lines.append(line)
            if line.startswith('exits '):assert line=='exits 0 20';break
        restored=race.stdout.readline().strip();race_lines.append(restored);assert restored==before
        for pid in owned:assert not alive(pid),('race survivor',pid)
        race.stdin.write('end\n');race.stdin.flush();os.close(master);os.close(slave);master=slave=-1;race.wait(timeout=5)
        results.append(dict(label='simultaneous-canonical-acquisition',original=before,restored=restored))
        print('PASS simultaneous-canonical-acquisition',flush=True)
    finally:
        if race.poll() is None:race.kill();race.wait(timeout=5)
        for fd in (master,slave):
            if fd>=0:os.close(fd)
        (evidence/'simultaneous-canonical-acquisition.log').write_text('\n'.join(race_lines)+'\n')
    (evidence/'summary.json').write_text(json.dumps(results,indent=2));print('PASS terminal boundary',evidence)


if __name__=='__main__':main()
