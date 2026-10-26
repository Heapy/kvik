#!/usr/bin/env python3
"""Verify the IDEA projection using the installed IDE's Kotlin resolver and diagnostics."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import shutil
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--idea-home', type=Path, required=True, help='IntelliJ IDEA.app/Contents')
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--stdlib', type=Path, required=True)
    parser.add_argument('--prepared-case', type=Path, help='Passed test_idea_export_cli.py case to import instead of the renderer fixture')
    parser.add_argument('--common-stdlib', type=Path, help='Pinned stdlib metadata root JAR for the common refinement renderer fixture')
    args = parser.parse_args()
    assert not (args.prepared_case and args.common_stdlib), 'Use either the CLI export or common renderer fixture'
    idea, java, native, stdlib = [p.resolve(strict=True) for p in (args.idea_home, args.java_home, args.konan_home, args.stdlib)]
    product = json.loads((idea/'Resources/product-info.json').read_bytes())
    assert product['buildNumber'] == '263.7307.44', 'Update and verify the test inspector against this IDE version first'
    parent = ROOT/'.kvik/tests/idea-import'
    parent.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=parent))
    print('Fixture:', case, flush=True)
    project, workspace = case/'project-normal', case/'workspace ü # % &'
    project.mkdir(); workspace.mkdir()
    prepared = None
    common_library = None
    if args.common_stdlib:
        # Fixture setup only: production preparation must select fragments from locked metadata.
        common_library = case/'stdlib-common.klib'
        with zipfile.ZipFile(args.common_stdlib) as archive, zipfile.ZipFile(common_library, 'w') as output:
            for entry in archive.infolist():
                if entry.filename.startswith('commonMain/') and not entry.is_dir():
                    output.writestr(entry.filename.removeprefix('commonMain/'), archive.read(entry))
    if args.prepared_case:
        prepared = json.loads((args.prepared_case/'summary.json').read_bytes())
        assert prepared['passed']
        workspace = Path(prepared['workspace']).resolve(strict=True)
    env = dict(os.environ, JAVA_HOME=str(java), KONAN_DATA_DIR=str(Path.home()/'.konan'), KONAN_USE_COMPILER_SERVER='false')

    def run(label, command, environment=env):
        with (case/f'{label}.stdout').open('wb') as out, (case/f'{label}.stderr').open('wb') as err:
            result = subprocess.run(list(map(str, command)), env=environment, cwd=ROOT, stdout=out, stderr=err, timeout=180)
        if result.returncode:
            raise RuntimeError(f'{label} exited {result.returncode}: {case}/{label}.stderr')
        return (case/f'{label}.stdout').read_text()

    if prepared is None:
        run('compile-renderer', [native/'bin/konanc', ROOT/'core/src/kvik/core/NativeTarget.kt',
            ROOT/'cli/src/kvik/cli/IdeaProjectFiles.kt', ROOT/'tests/idea/ProjectFixture.kt',
            '-entry', 'kvik.cli.main', '-target', 'macos_arm64', '-Xoverride-konan-properties=airplaneMode=true', '-o', case/'fixture'])

    def export(mode=None):
        nonlocal project
        # A changed export is imported as a new project generation. This does not
        # claim live IDE model reload: inspect.sh can retain an old cached .iml model.
        project = case/f'project-{mode or "normal"}'
        project.mkdir(exist_ok=True)
        if prepared:
            shutil.copytree(prepared['project'], project, dirs_exist_ok=True)
            if mode == 'without-friend':
                path = project/'.idea/modules/app.test.iml'
                document = ET.parse(path)
                facet = document.find('.//facet/configuration')
                facet.remove(facet.find('additionalVisibleModuleNames'))
                document.write(path, encoding='utf-8', xml_declaration=True)
            return
        extra = [mode or 'normal', common_library] if common_library else ([mode] if mode else [])
        output = run('render-' + (mode or 'normal'), [case/'fixture.kexe', workspace, stdlib, *extra])
        for line in output.splitlines():
            name, content = line.split('\t')
            path = project/name
            assert path.is_relative_to(project) and '..' not in path.parts
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(bytes.fromhex(content))
        for name in ('hidden', 'base', 'app', 'tests', 'common', 'shared'):
            (workspace/name).mkdir(exist_ok=True)

    export()
    if prepared is None:
        (workspace/'hidden/Hidden.kt').write_text('package hidden\nfun hiddenValue(): String = "42"\n')
        (workspace/'base/Base.kt').write_text('package base\nimport hidden.hiddenValue\nfun baseValue(): String = hiddenValue()\n')
    source = workspace/'app/App.kt'
    good = source.read_text() if prepared else 'package app\nimport base.baseValue\ninternal fun answer(): String = baseValue()\n'
    if common_library:
        common_source = workspace/'common/Common.kt'
        common_good = ('package app\nexpect fun platformName(): String\ninternal fun secret(): String = "secret"\n'
                       'fun commonValue(): String = listOf("common").first()\n')
        common_source.write_text(common_good)
        (workspace/'shared/Shared.kt').write_text('package app\nfun sharedValue(): String = commonValue() + secret()\n')
        good += ('actual fun platformName(): String = java.lang.System.getProperty("os.name")\n'
                 'fun useCommon(): String = platformName() + sharedValue() + secret()\n')
    source.write_text(good + 'fun broken(): String = missingSymbol()\n')
    if prepared is None:
        (workspace/'tests/Tests.kt').write_text('package app\nfun checkAnswer(): String = answer()\n')

    # Load a fixture-only inspector in an isolated plugin directory. The exported project
    # does not depend on it; it observes the ordinary Kotlin IDE analysis and resolution.
    classes = case/'classes'; classes.mkdir()
    jars = [*(idea/'lib').rglob('*.jar'), *(idea/'plugins/Kotlin/lib').rglob('*.jar')]
    run('compile-inspector', [java/'bin/javac', '-proc:none', '-classpath', os.pathsep.join(map(str, jars)),
        '-d', classes, ROOT/'tests/idea/KvikReferenceProbe.java', ROOT/'tests/idea/KvikRefreshRoots.java'])
    plugin = case/'plugins/kvik-reference-probe/lib/probe.jar'; plugin.parent.mkdir(parents=True)
    with zipfile.ZipFile(plugin, 'w') as archive:
        for path in classes.rglob('*.class'):
            archive.write(path, str(path.relative_to(classes)))
        archive.writestr('META-INF/plugin.xml', '''<idea-plugin><id>io.heapy.kvik.reference-probe</id>
<name>Kvik reference probe</name><version>0.1</version><vendor>Heapy</vendor>
<depends>com.intellij.modules.platform</depends><depends>org.jetbrains.kotlin</depends>
<extensions defaultExtensionNs="com.intellij"><localInspection language="kotlin" shortName="KvikReferenceProbe"
displayName="Kvik reference probe" groupName="Kvik probe" enabledByDefault="true" level="ERROR"
implementationClass="io.heapy.kvik.probe.KvikReferenceProbe"/>
<commandLineInspectionProjectConfigurator implementation="io.heapy.kvik.probe.KvikRefreshRoots"/>
</extensions></idea-plugin>''')
        archive.writestr('inspectionDescriptions/KvikReferenceProbe.html', '<html><body>Observe Kotlin references and errors in the Kvik import fixture.</body></html>')
    config = case/'config/options'; config.mkdir(parents=True)
    table = ET.Element('application')
    sdk = ET.SubElement(ET.SubElement(table, 'component', name='ProjectJdkTable'), 'jdk', version='2')
    for tag, value in [('name', 'kvik-probe'), ('type', 'JavaSDK'), ('version', 'java version "25.0.4"'), ('homePath', str(java))]:
        ET.SubElement(sdk, tag, value=value)
    roots = ET.SubElement(sdk, 'roots')
    classes_root = ET.SubElement(ET.SubElement(roots, 'classPath'), 'root', type='composite')
    for module in sorted((java/'jmods').glob('*.jmod')):
        ET.SubElement(classes_root, 'root', url=f'jrt://{java}!/{module.stem}', type='simple')
    assert len(classes_root) > 1
    ET.SubElement(ET.SubElement(roots, 'sourcePath'), 'root', type='composite')
    ET.ElementTree(table).write(config/'jdk.table.xml', encoding='utf-8', xml_declaration=True)
    options = case/'idea.vmoptions'
    options.write_text('\n'.join([*(f'-Didea.{key}.path={case/value}' for key, value in
        [('config', 'config'), ('system', 'system'), ('plugins', 'plugins'), ('log', 'log')]),
        '-Dide.inspect.activity.based.inspections.enabled=true', '']))
    profile = case/'profile.xml'
    profile.write_text('''<component name="InspectionProjectProfileManager"><profile version="1.0">
<option name="myName" value="Kvik import"/><inspection_tool class="KvikReferenceProbe" enabled="true"
level="ERROR" enabled_by_default="true"/></profile></component>''')
    inspect_env = dict(env, IDEA_VM_OPTIONS=str(options))

    def inspect(phase):
        reports = case/f'{phase}-reports'
        run(phase, [idea/'bin/inspect.sh', project, profile, reports, '-v2'], inspect_env)
        problems = ET.parse(reports/'KvikReferenceProbe.xml').getroot().findall('problem')
        values = [(p.findtext('file'), p.findtext('description')) for p in problems]
        assert any('KVIK_REFERENCE String RESOLVED' == message for _, message in values), values
        assert any('KVIK_REFERENCE baseValue RESOLVED' == message for _, message in values), values
        if prepared:
            assert any('KVIK_REFERENCE generatedValue RESOLVED' == message for _, message in values), values
        if common_library:
            for name in ('listOf', 'first', 'platformName', 'sharedValue', 'commonValue'):
                assert any(f'KVIK_REFERENCE {name} RESOLVED' == message for _, message in values), (name, values)
        settings = [text.split('|') for _, text in values if text.startswith('KVIK_RUN_CONFIGURATION|')]
        assert {s[1] for s in settings} == ({'Kvik build', 'Kvik test'} if prepared else {'Kvik quoted command'}), settings
        for setting in settings:
            assert setting[2] == 'ShConfigurationType' and setting[4:] == ['/bin/sh', 'true', 'false'], setting
            assert Path(setting[3]).is_file(), setting
        return [(file, text) for file, text in values if text.startswith('KVIK_DIAGNOSTIC')]

    broken = inspect('broken')
    assert len(broken) == 1 and 'missingSymbol' in broken[0][1], broken
    source.write_text(good)
    repaired = inspect('repaired')
    assert repaired == [], repaired
    source.write_text(good + 'fun privateLeak(): String = hidden.hiddenValue()\n')
    private = inspect('private-edge')
    assert private and all('hidden' in text for _, text in private), private
    source.write_text(good)
    export('without-friend')
    friend = inspect('without-friend')
    assert friend and all('answer' in text for _, text in friend), friend
    export('restored')
    restored = inspect('restored')
    assert restored == [], restored
    phases = ['broken', 'repaired', 'private-edge', 'without-friend', 'restored']
    if common_library:
        common_source.write_text(common_good + 'fun platformLeak(): String = java.lang.System.getProperty("os.name")\n')
        leak = inspect('common-platform-leak')
        assert len(leak) == 1 and 'Common.kt' in leak[0][0] and "'java'" in leak[0][1], leak
        common_source.write_text(common_good)
        export('without-refinement')
        refinement = inspect('without-refinement')
        assert len(refinement) == 2 and all('App.kt' in file for file, _ in refinement), refinement
        assert any("no corresponding 'expect'" in text for _, text in refinement), refinement
        assert any('secret' in text and 'internal' in text for _, text in refinement), refinement
        export('refinement-restored')
        assert inspect('refinement-restored') == []
        phases += ['common-platform-leak', 'without-refinement', 'refinement-restored']
    if prepared is None:
        output = run('quoted-command', ['/bin/sh', project/'.idea/kvik-command-0.sh'])
        assert output == "quote' dollar$ backtick` space & < >", output
    (case/'summary.json').write_text(json.dumps(dict(passed=True, build=product['buildNumber'],
        phases=phases,
        modelRefresh='new-project-import', sourceRefresh='explicit-content-root-refresh',
        projectSource='native-cli-export' if prepared else 'native-common-renderer-fixture' if common_library else 'native-renderer-fixture'), indent=2) + '\n')
    print('Passed:', case, flush=True)


if __name__ == '__main__':
    main()
