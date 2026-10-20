#!/usr/bin/env python3
"""Read-only Skerry toolchain check. Does not run Gradle, read credentials or dump env."""
import argparse
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tomllib


def probe(command):
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=15)
        # Version output only, bounded. No auth/status/config commands here.
        return {'exit': result.returncode, 'version': (result.stdout + result.stderr).strip()[:500]}
    except (OSError, subprocess.TimeoutExpired) as exc:
        return {'exit': None, 'error': type(exc).__name__}


def inspect(repo, server_only=False):
    result = {'platform': platform.system(), 'python': platform.python_version(), 'repo': str(repo), 'checks': [], 'tools': {}}
    checks = result['checks']
    checks.append({'name': 'python>=3.11', 'ok': sys.version_info >= (3, 11)})
    for tool, flag in [('git', '--version'), ('java', '-version'), ('javac', '-version')]:
        result['tools'][tool] = probe([tool, flag])
    if (repo / 'gradle/libs.versions.toml').is_file():
        versions = tomllib.loads((repo / 'gradle/libs.versions.toml').read_text(encoding='utf-8'))['versions']
        result['declared_versions'] = {k: versions[k] for k in ['kotlin', 'composeMultiplatform', 'agp', 'android-compileSdk', 'android-buildTools', 'android-targetSdk', 'android-minSdk']}
    else:
        checks.append({'name': 'Skerry version catalog', 'ok': False})
    for tool in ['java', 'javac']:
        version = result['tools'][tool].get('version', '')
        checks.append({'name': tool + ' 21', 'ok': bool(re.search(r'(?:version\s+\"|javac\s+)21(?:\.|\"|\s|$)', version))})
    java_home = Path(os.environ['JAVA_HOME']) if os.environ.get('JAVA_HOME') else None
    checks.append({'name': 'JAVA_HOME has compiler', 'ok': bool(java_home and (java_home / 'bin' / ('javac.exe' if os.name == 'nt' else 'javac')).is_file())})
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    local = repo / 'local.properties'
    if not sdk and local.is_file():
        for line in local.read_text(encoding='utf-8', errors='replace').splitlines():
            if line.startswith('sdk.dir='):
                sdk = line.split('=', 1)[1].replace('\\:', ':').replace('\\\\', '\\')
    if not server_only:
        for name, path in [('android-sdk', Path(sdk) if sdk else None)]:
            checks.append({'name': name, 'ok': bool(path and path.is_dir())})
        if sdk and 'declared_versions' in result:
            v = result['declared_versions']
            for item in [f"platforms/android-{v['android-compileSdk']}/android.jar", f"build-tools/{v['android-buildTools']}"]:
                checks.append({'name': item, 'ok': (Path(sdk) / item).exists()})
        if platform.system() == 'Linux':
            checks.append({'name': 'Compose display or xvfb-run', 'ok': bool(os.environ.get('DISPLAY') or shutil.which('xvfb-run'))})
    for file in ['settings.gradle.kts', 'tools/harness/gate.py', 'gradlew', 'gradle/wrapper/gradle-wrapper.jar']:
        checks.append({'name': file, 'ok': (repo / file).is_file()})
    if Path('/proc/meminfo').is_file():
        memory = {line.split(':')[0]: int(line.split()[1]) for line in Path('/proc/meminfo').read_text().splitlines()}
        result['memory_mib'] = {k: memory[k] // 1024 for k in ['MemAvailable', 'SwapTotal', 'SwapFree']}
        result['swap_free_kib'] = memory['SwapFree']  # Avoid rounding tiny remaining swap to a misleading zero.
        checks.append({'name': 'available-memory>=6GiB', 'ok': memory['MemAvailable'] >= 6 * 1024 * 1024})
        checks.append({'name': 'swap-not-full', 'ok': memory['SwapTotal'] == 0 or memory['SwapFree'] > 0})
    result['note'] = 'Tool discovery only. Run gate.py doctor separately; no builds or native/device validation performed.'
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, required=True)
    parser.add_argument('--server-only', action='store_true')
    args = parser.parse_args()
    result = inspect(args.repo.expanduser().resolve(), args.server_only)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return int(any(not c['ok'] for c in result['checks']))


if __name__ == '__main__':
    raise SystemExit(main())
