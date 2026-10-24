"""Optional consumer SDK pinning must not widen bootstrap host locks."""
from contextlib import contextmanager
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from bootstrap import pin_tools


@contextmanager
def darwin():
    with patch.object(pin_tools.platform, 'system', return_value='Darwin'), \
            patch.object(pin_tools.platform, 'machine', return_value='arm64'):
        yield


class IosPinningTest(unittest.TestCase):
    def test_target_sdk_selection_and_platform_validation(self):
        with tempfile.TemporaryDirectory() as temporary, darwin():
            developer = Path(temporary) / 'Xcode/Contents/Developer'
            clang = developer / 'Toolchains/XcodeDefault.xctoolchain/usr/bin/clang'
            mac = developer / 'Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk'
            device = developer / 'Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS.sdk'
            simulator = developer / 'Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator.sdk'
            for path, platform in ((device, 'iphoneos'), (simulator, 'iphonesimulator')):
                path.mkdir(parents=True)
                (path / 'SDKSettings.json').write_text(json.dumps({'Version': '27.1', 'CanonicalName': platform + '27.1',
                    'DefaultProperties': {'PLATFORM_NAME': platform}, 'SupportedTargets': {platform: {}}}))
            with patch.object(pin_tools.subprocess, 'check_output', side_effect=map(str, (mac, clang, device, simulator))) as process:
                roots = pin_tools.tool_roots('/jdk', '/native', '/konan', ['iosSimulatorArm64', 'iosArm64'])
                self.assertEqual(device.resolve(), roots['iosSdk'])
                self.assertEqual(simulator.resolve(), roots['iosSimulatorSdk'])
                self.assertEqual(pin_tools.component_names() | {'iosSdk', 'iosSimulatorSdk'}, set(roots))
                self.assertEqual(['xcrun', '--sdk', 'iphoneos', '--show-sdk-path'], process.call_args_list[2].args[0])
            with patch.object(pin_tools.subprocess, 'check_output', side_effect=map(str, (mac, clang, simulator))):
                with self.assertRaisesRegex(ValueError, 'selected Xcode'):
                    pin_tools.tool_roots('/jdk', '/native', '/konan', ['iosArm64'])
            with self.assertRaisesRegex(ValueError, 'required iphoneos'):
                pin_tools.validate_target_sdk(simulator, 'iphoneos')
            (device / 'SDKSettings.json').write_text(json.dumps({'Version': '27.1\n'}))
            with self.assertRaisesRegex(ValueError, 'version'):
                pin_tools.validate_target_sdk(device, 'iphoneos')

    def test_optional_sdk_flags_refuse_bootstrap_output_before_hashing(self):
        base = ['pin_tools', '--java-home', '/jdk', '--konan-home', '/native', '--apple-target', 'iosArm64']
        for extra in ([], ['--output', str(ROOT / 'bootstrap/toolchains.lock.json')]):
            with patch.object(sys, 'argv', base + extra), patch.object(pin_tools, 'inventory') as inventory:
                with self.assertRaises(SystemExit) as failure:
                    pin_tools.main()
                self.assertEqual(2, failure.exception.code)
                inventory.assert_not_called()

    def test_invalid_targets_or_linux_rejected_before_tools(self):
        with darwin():
            for targets in (['macosArm64'], ['iosArm64', 'iosArm64']):
                with self.assertRaises(ValueError):
                    pin_tools.validate_apple_targets(targets)
        with patch.object(pin_tools.platform, 'system', return_value='Linux'), \
                patch.object(pin_tools.platform, 'machine', return_value='x86_64'), \
                patch.object(pin_tools.subprocess, 'check_output') as process:
            with self.assertRaisesRegex(ValueError, 'Darwin'):
                pin_tools.tool_roots('/jdk', '/native', '/konan', ['iosArm64'])
            process.assert_not_called()

    def test_consumer_output_explicitly_contains_optional_pins(self):
        with tempfile.TemporaryDirectory() as temporary, darwin():
            output = Path(temporary) / 'consumer.lock.json'
            roots = {name: Path(temporary) / name for name in pin_tools.component_names() | {'iosSdk'}}
            with patch.object(sys, 'argv', ['pin_tools', '--java-home', '/jdk', '--konan-home', '/native',
                    '--apple-target', 'iosArm64', '--output', str(output)]), \
                    patch.object(pin_tools, 'tool_roots', return_value=roots) as get_roots, \
                    patch.object(pin_tools, 'host_identity', return_value={}), \
                    patch.object(pin_tools, 'inventory', return_value=[]):
                pin_tools.main()
            self.assertEqual(set(roots), set(json.loads(output.read_bytes())['components']))
            self.assertEqual(['iosArm64'], get_roots.call_args.args[3])


if __name__ == '__main__':
    unittest.main()
