"""Host-specific installation roots shared by composed CLI fixtures."""
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from bootstrap.pin_tools import native_target, tool_roots


def installation_roots(java_home, konan_home, konan_data, developer_directory):
    if native_target() == 'linux_x64':
        return {name: str(path) for name, path in tool_roots(java_home, konan_home, konan_data).items()}
    developer = developer_directory.resolve()
    data = konan_data.resolve()
    return dict(jdk=str(java_home.resolve()), kotlinNative=str(konan_home.resolve()),
                llvm=str(data/'dependencies/llvm-21-aarch64-macos-essentials-97'),
                libffi=str(data/'dependencies/libffi-3.3-1-macos-arm64'),
                sdk=str((developer/'Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk').resolve()),
                xcodeToolchain=str(developer/'Toolchains/XcodeDefault.xctoolchain'))


def host_native_target():
    return {'macos_arm64': 'macosArm64', 'linux_x64': 'linuxX64'}[native_target()]
