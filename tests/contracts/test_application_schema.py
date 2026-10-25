import json
from pathlib import Path
import unittest
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT/'schemas/v1/contracts.json').read_bytes())
TARGET = Draft202012Validator({**SCHEMA, '$ref': '#/$defs/target'})
LAUNCH = Draft202012Validator({**SCHEMA, '$ref': '#/$defs/applicationLaunch'})


class ApplicationSchemaTest(unittest.TestCase):
    def target(self, platform='jvm', settings=None):
        return dict(name=platform, main='main', test=None, compilerOptions=[], application=settings or {'entryPoint':'sample.MainKt'})

    def test_optional_application_settings_and_target_specific_entries(self):
        for platform in ('jvm', 'macosArm64', 'js', 'wasmJs'):
            original=self.target(platform);del original['application'];TARGET.validate(original)
            TARGET.validate(self.target(platform, dict(entryPoint='sample.main',arguments=['--input','space value','@literal',''],environment={'MODE':'dev'})))
        TARGET.validate(self.target(settings=dict(entryPoint='sample.Outer$Nested')))
        self.assertFalse(TARGET.is_valid(self.target('macosArm64', dict(entryPoint='sample.Outer$Nested'))))
        for settings in [None,[],{},dict(entryPoint='MainKt',command='sh'),dict(entryPoint='MainKt',arguments=None),dict(entryPoint='MainKt',environment={'X':True})]:
            value=self.target();value['application']=settings
            self.assertFalse(TARGET.is_valid(value),settings)

    def test_bounds_invalid_names_nuls_and_unknown_fields(self):
        for entry in ['', 'a..Main', 'MainKt\n', '/MainKt', '-jar','x'*1025]:
            self.assertFalse(TARGET.is_valid(self.target(settings=dict(entryPoint=entry))))
        for change in [dict(arguments=['a\0b']),dict(arguments=['']*257),dict(arguments=['x'*4097]),
            dict(environment={'BAD=KEY':'x'}),dict(environment={'X\n':'x'}),dict(environment={'X':'a\0b'}),
            dict(environment={'X'+str(i):'' for i in range(129)})]:
            self.assertFalse(TARGET.is_valid(self.target(settings=dict(entryPoint='MainKt',**change))))

    def test_launch_alternatives_order_and_declared_shape(self):
        jvm=dict(schemaVersion=1,module='app',target='jvm',application={'entryPoint':'MainKt'},classpath=['classes','deps/runtime.jar'],executable=None)
        native={**jvm,'target':'macosArm64','application':{'entryPoint':'sample.main'},'classpath':[],'executable':'bin/app.kexe'}
        for value in (jvm,native): LAUNCH.validate(value)
        for change in [dict(classpath=[]),dict(classpath=['classes','classes']),dict(executable='bin/app.kexe'),dict(classpath=['../escape']),dict(classpath=['x\n']),dict(unknown=True),dict(module='app\n'),dict(schemaVersion=2)]:
            self.assertFalse(LAUNCH.is_valid({**jvm,**change}),change)
        for change in [dict(classpath=['classes']),dict(executable=None),dict(application={'entryPoint':'Outer$Nested'}),dict(executable='/absolute'),dict(executable='x\n')]:
            self.assertFalse(LAUNCH.is_valid({**native,**change}),change)

    def test_node_module_launch(self):
        for target in ('js', 'wasmJs'):
            launch=dict(schemaVersion=1,module='app',target=target,application={'entryPoint':'sample.main'},
                        classpath=[],executable=None,moduleFile='program/launch.mjs')
            LAUNCH.validate(launch)
            for change in [dict(moduleFile=None),dict(moduleFile='program/app.wasm'),dict(executable='app'),
                           dict(classpath=['classes']),dict(target='jvm'),dict(target='macosArm64')]:
                self.assertFalse(LAUNCH.is_valid({**launch,**change}),change)
            del launch['moduleFile']
            self.assertFalse(LAUNCH.is_valid(launch))


if __name__=='__main__':unittest.main()
