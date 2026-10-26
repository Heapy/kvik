#!/usr/bin/env python3
"""Generate only the independent K16 package-query schema."""
import json
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
def ref(name): return {'$ref': '#/$defs/' + name}
def obj(**properties): return {'type':'object','properties':properties,'required':list(properties),'additionalProperties':False}
def arr(items, maximum=8192): return {'type':'array','items':items,'maxItems':maximum}
def enum(*values): return {'enum':list(values)}
def nullable(value): return {'anyOf':[value,{'type':'null'}]}
STRICT_END=r'$(?![\s\S])'
text={'type':'string','maxLength':4096}; boolean={'type':'boolean'}; nonnegative={'type':'integer','minimum':0}
coordinate={'type':'string','pattern':r'^[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+'+STRICT_END,'maxLength':1024}
package={'type':'string','pattern':r'^[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+'+STRICT_END,'maxLength':512}
channel=enum('stable','prerelease','unclassified'); freshness=enum('fresh','stale','missing','indeterminate')
time={'type':'string','format':'date-time','maxLength':64}; mode=enum('cache','refresh','offline')
defs={
 'digest':obj(hash={'type':'string','pattern':'^[0-9a-f]{64}'+STRICT_END},sizeBytes=nonnegative),
 'repository':obj(id=text,url=text),
 'metadata':obj(url=text,digest=ref('digest')),
 'artifact':obj(coordinate=coordinate,variant=text,repository=text,url=text,digest=ref('digest')),
 'variant':obj(platform=enum('jvm','macosArm64','linuxX64','iosArm64','iosSimulatorArm64','js','wasmJs','common'),usage=text,attributes={'type':'object','additionalProperties':text}),
 'domain':obj(module=text,target=enum('common','jvm','macosArm64','linuxX64','iosArm64','iosSimulatorArm64','js','wasmJs'),compilation=enum('main','test'),usage=enum('java-api','java-runtime','kotlin-api','kotlin-runtime','kotlin-metadata'),purpose=enum('dependencies','compiler-plugin','ksp-runtime','symbol-processor','sqldelight-compiler')),
 'diagnostic':obj(code=text,message=text,source=nullable(text)),
 'source':obj(repository=text,url=text,kind=enum('metadata','artifact'),digest=nullable(ref('digest')),observedAt=nullable(time),cache=enum('network','cache','locked','none'),status=enum('found','not-found','missing','corrupt','error'),freshness=freshness,latest=nullable(text),release=nullable(text),lastUpdated=nullable(text)),
 'requirement':obj(coordinate=coordinate,semantics=enum('requires','prefers','pom-effective','redirect','constraint','explicit-exclusion','metadata-exclusion')),
 'node':obj(id=text,coordinate=coordinate,platform=boolean,expanded=boolean,variant=text,repository=text,metadata=arr(ref('metadata'),32),artifacts=arr(ref('artifact'),32)),
 'edge':obj(source=text,target=nullable(text),kind=enum('dependency','redirect','constraint','excluded'),requirement=ref('requirement'),selected=nullable(coordinate),transitive=boolean),
 'root':obj(requested=coordinate,node=text),
 'path':obj(root=coordinate,node=text,nodes=arr(text,1024),edges=arr(nonnegative,1024)),
 'graph':obj(variant=ref('variant'),roots=arr(ref('root'),256),nodes=arr(ref('node'),1024),edges=arr(ref('edge'),8192),paths=arr(ref('path'),4096),pathPolicy=enum('shortest-per-root-node'),truncated=boolean,artifactVerification=enum('verified')),
 'version':obj(version=text,channel=channel,repositories=arr(text,32)),
 'declaration':obj(module=text,sourceSet=nullable(text),kind=enum('external','module','compiler-plugin','ksp-runtime','symbol-processor','sqldelight-compiler'),coordinate=nullable(coordinate),dependencyModule=nullable(text),scope=enum('compile','runtime'),exported=boolean,targets=arr(text,32),compilations=arr(enum('main','test'),2)),
 'scopedGraph':obj(domain=ref('domain'),graph=ref('graph')),
 'versionQuery':obj(project=text,coordinate=package,repositories=arr(ref('repository'),32),channel=enum('all','stable','prerelease'),limit={'type':'integer','minimum':1,'maximum':200},cursor=nullable(text),mode=mode),
 'packageQuery':obj(project=text,coordinate=coordinate,repositories=arr(ref('repository'),32),variant=ref('variant'),pins=arr(coordinate,256),mode=mode),
 'projectQuery':obj(project=text,lock=text,store=text,modules=arr(text,1024),targets=arr(enum('jvm','macosArm64','linuxX64','iosArm64','iosSimulatorArm64','js','wasmJs'),7),compilation=nullable(enum('main','test')),usage=nullable(enum('java-api','java-runtime','kotlin-api','kotlin-runtime'))),
}
script_id={'type':'string','maxLength':128,'pattern':r'^[A-Za-z][A-Za-z0-9_.-]*'+STRICT_END}
defs['domain']['allOf']=[{'if':{'properties':{'purpose':{'const':'sqldelight-compiler'}}},'then':{'properties':{'target':{'const':'jvm'},'compilation':{'const':'main'},'usage':{'const':'java-runtime'}}}}]
defs['domain']['properties']['sourceSet']={'type':'string','pattern':r'^[A-Za-z][A-Za-z0-9_.-]*'+STRICT_END}
defs['domain']['allOf'] += [
    {'if':{'properties':{'target':{'const':'common'}}},
     'then':{'properties':{'usage':{'const':'kotlin-metadata'},'purpose':{'const':'dependencies'}},
             'anyOf':[{'properties':{'compilation':{'const':'main'}}},{'required':['sourceSet']}]}},
    {'if':{'anyOf':[{'required':['sourceSet']},{'properties':{'usage':{'const':'kotlin-metadata'}}}]},
     'then':{'properties':{'target':{'const':'common'},'purpose':{'const':'dependencies'}}}},
]
defs['moduleDomain']=defs['domain']
defs['scriptDomain']=obj(script=script_id,target={'const':'jvm'},compilation={'const':'script'},usage=enum('java-api','java-runtime'),purpose=enum('dependencies','compiler-plugin'))
defs['scriptDomain']['allOf']=[{'if':{'properties':{'purpose':{'const':'compiler-plugin'}}},'then':{'properties':{'usage':{'const':'java-runtime'}}}}]
for name in ('moduleDomain', 'scriptDomain'):
    defs[name]['properties']['pluginId'] = {'type':'string','pattern':r'^[A-Za-z][A-Za-z0-9_.-]*'+STRICT_END}
    defs[name]['allOf'].append({'if':{'required':['pluginId']},'then':{'properties':{'purpose':{'const':'compiler-plugin'},'usage':{'const':'java-runtime'}}}})
defs['domain']={'oneOf':[ref('moduleDomain'),ref('scriptDomain')]}
defs['moduleDeclaration']=defs['declaration']
defs['scriptOrigin']=obj(declaration=text,profileSelection=nullable(text),catalog=nullable(text))
script_declaration=dict(defs['moduleDeclaration']['properties']);script_declaration.pop('module')
script_declaration.update(script=script_id,origin=ref('scriptOrigin'),kind=enum('external','module','compiler-plugin'),sourceSet={'type':'null'},exported={'const':False},targets={'const':['jvm']},compilations={'const':['script']})
defs['scriptDeclaration']=obj(**script_declaration)
for name in ('moduleDeclaration', 'scriptDeclaration'):
    defs[name]['properties'].update(pluginId={'type':'string','pattern':r'^[A-Za-z][A-Za-z0-9_.-]*'+STRICT_END},
                                    compilerVersion=enum('2.4.10','2.4.20','2.4.21'))
    defs[name]['allOf']=[{'if':{'anyOf':[{'required':['pluginId']},{'required':['compilerVersion']}]},
                         'then':{'required':['pluginId','compilerVersion'],'properties':{'kind':{'const':'compiler-plugin'}}}},
                        {'if':{'required':['compilerVersion'],'properties':{'compilerVersion':enum('2.4.10','2.4.21')}},
                         'then':{'properties':{'targets':{'const':['jvm']}}}}]
defs['declaration']={'oneOf':[ref('moduleDeclaration'),ref('scriptDeclaration')]}
defs['projectQuery']['properties']['scripts']=arr(script_id,256)
defs['projectQuery']['properties']['compilation']=nullable(enum('main','test','script'))
rule_text={'type':'string','minLength':1,'pattern':r'^[^\u0000-\u001f]*[^\s\u0000-\u001f][^\u0000-\u001f]*'+STRICT_END}
defs['metadataExclusion'] = obj(parent=coordinate, metadata={'allOf':[ref('digest'),{'properties':{'sizeBytes':{'minimum':1,'maximum':1048576}}}]},
    variant={**rule_text,'maxLength':256}, dependency=coordinate, reason={**rule_text,'maxLength':1024})
defs['edge']['properties']['exclusion'] = obj(rule=ref('metadataExclusion'), digest=ref('digest'))
defs['edge']['allOf'] = [{'if': {'properties': {'kind': {'const':'excluded'}}},
    'then': {'properties': {'target':{'type':'null'}, 'selected':{'type':'null'}, 'transitive':{'const':False},
        'requirement': {'properties': {'semantics': enum('explicit-exclusion','metadata-exclusion')}}},
        'allOf': [{'if': {'properties': {'requirement': {'properties': {'semantics': {'const':'explicit-exclusion'}}}}},
                   'then': {'required':['exclusion']}, 'else': {'not': {'required':['exclusion']}}}]},
    'else': {'not': {'required':['exclusion']}, 'properties': {'requirement': {'properties': {'semantics':enum('requires','prefers','pom-effective','redirect','constraint')}}}}}]

base=dict(schemaVersion={'const':1},status=enum('complete','incomplete','unsupported'),observedAt=time,freshness=freshness,freshnessBasis=enum('repository-observation','locked-inputs'),sources=arr(ref('source')),diagnostics=arr(ref('diagnostic'),1024),compatibility={'const':'not-assessed'},truncated=boolean)
def result(kind,query,**payload):return obj(**base,kind={'const':kind},query=ref(query),**payload)
defs['versions']=result('package-versions','versionQuery',versions=arr(ref('version'),200),order={'const':'lexical-ascending'},classification={'const':'numeric-known-qualifiers-v1'},page=obj(limit={'type':'integer','minimum':1,'maximum':200},nextCursor=nullable(text),snapshot=nullable(ref('digest')),total=nullable(nonnegative)))
defs['packageDependencies']=result('package-dependencies','packageQuery',graph=nullable(ref('graph')))
defs['projectDependencies']=result('project-dependencies','projectQuery',configuration=nullable(ref('digest')),dependencyLock=nullable(ref('digest')),declarations=arr(ref('declaration'),16384),declarationsScope={'const':'all-project'},domains=arr(ref('scopedGraph'),256))
defs['projectDependencies']['properties']['availableVersions']=obj(packageLimit={'const':32},totalPackages=nonnegative,
    packages=arr(obj(lockedVersions=arr(text,262144),domains=arr(ref('domain'),256),available=ref('versions')),32))
# Keep source-backed query contracts when regenerating the package schema.
contracts=json.loads((ROOT/'schemas/v1/contracts.json').read_text())['$defs']
for name in ('sourceReference', 'sourceProvenance'):
    defs[name]=contracts[name]
for name in ('versionQuery', 'versions', 'packageDependencies', 'projectDependencies'):
    defs[name]['properties']['source']=ref('sourceReference')
schema={'$schema':'https://json-schema.org/draft/2020-12/schema','$id':'https://kvik.dev/schemas/v1/packages.json','$defs':defs,'oneOf':[ref('versions'),ref('packageDependencies'),ref('projectDependencies')]}
(ROOT/'schemas/v1/packages.json').write_text(json.dumps(schema,indent=2)+'\n')
