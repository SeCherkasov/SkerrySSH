#!/usr/bin/env python3
"""Run Skerry heavy commands in a Linux user cgroup. No unbounded fallback."""
import argparse
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import uuid

if platform.system() == 'Linux':
    import fcntl


def preflight():
    errors = []
    if platform.system() != 'Linux':
        return ['Linux/systemd wrapper only; configure an equivalent host limit before heavy work.']
    for name in ['systemd-run', 'systemctl']:
        if not shutil.which(name):
            errors.append(name + ' is unavailable')
    info = {line.split(':')[0]: int(line.split()[1]) for line in Path('/proc/meminfo').read_text().splitlines()}
    if info['MemAvailable'] < 6 * 1024 * 1024:
        errors.append('Less than 6 GiB MemAvailable')
    if info['SwapTotal'] and not info['SwapFree']:
        errors.append('Swap is full')
    for proc in Path('/proc').iterdir():
        if not proc.name.isdigit():
            continue
        try:
            if proc.stat().st_uid != os.getuid():
                continue
            argv = (proc / 'cmdline').read_bytes().split(b'\0')
            # Report only PID/category. Do not print possibly sensitive Java arguments.
            if argv and Path(os.fsdecode(argv[0])).name.lower() in ('java', 'javac', 'gradle', 'gradlew', 'kotlinc'):
                raw = b' '.join(argv)
                if any(mark in raw for mark in [b'GradleDaemon', b'GradleWrapperMain', b'GradleWorkerMain', b'KotlinCompileDaemon', b'kotlinc', b'gradlew']):
                    errors.append(f'Competing build JVM/compiler PID {proc.name}; inspect ownership, do not mass-kill')
        except (OSError, ValueError):
            pass
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, required=True)
    parser.add_argument('--dry-run', action='store_true')
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    repo = args.repo.expanduser().resolve()
    if not (repo / 'tools/harness/gate.py').is_file():
        parser.error('--repo must name a Skerry checkout')
    command = args.command[1:] if args.command[:1] == ['--'] else args.command
    if not command:
        parser.error('Provide a command after --')
    launch = ['systemd-run', '--user', '--scope', '--quiet', '--unit=skerry-bounded-' + uuid.uuid4().hex[:12],
              '-p', 'MemoryMax=3G', '-p', 'MemorySwapMax=0', '-p', 'CPUQuota=200%', '--'] + command
    # GRADLE_OPTS configures this launch only; no user/repository config changes.
    overrides = '-Dorg.gradle.jvmargs=-Xmx1536m -Dorg.gradle.parallel=false -Dorg.gradle.workers.max=1 -Dorg.gradle.project.kotlin.compiler.execution.strategy=in-process'
    errors = preflight()
    if args.dry_run:
        print(json.dumps({'command': launch, 'GRADLE_OPTS': overrides, 'preflight_errors': errors,
                          'note': 'Plan only. Command arguments are user-supplied; never pass secrets on the command line.'}, indent=2))
        return int(bool(errors))
    if errors:
        parser.exit(2, '\n'.join(errors) + '\n')
    bus = subprocess.run(['systemctl', '--user', 'show', '--property=Version', '--value'], capture_output=True)
    if bus.returncode:
        parser.exit(2, 'User systemd bus unavailable. Restore the actual user session environment; no unbounded fallback.\n')
    java_home = os.environ.get('JAVA_HOME')
    if not java_home or not (Path(java_home) / 'bin/javac').is_file():
        parser.exit(2, 'Set JAVA_HOME to a compiler-equipped JDK 21 first.\n')
    version = subprocess.run([str(Path(java_home) / 'bin/java'), '-version'], capture_output=True, text=True)
    if 'version "21' not in version.stdout + version.stderr:
        parser.exit(2, 'JAVA_HOME must select JDK 21.\n')
    lock_dir = Path.home() / '.cache/skerry-development-skills'
    lock_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (lock_dir / 'heavy.lock').open('a') as handle:
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            parser.exit(2, 'Another bundle-managed heavy run is active.\n')
        # Recheck after acquiring our lock. The harness has its own independent user-wide lock.
        errors = preflight()
        if errors:
            parser.exit(2, '\n'.join(errors) + '\n')
        env = dict(os.environ, GRADLE_OPTS=overrides)
        env['PATH'] = str(Path(java_home) / 'bin') + os.pathsep + env.get('PATH', '')
        return subprocess.run(launch, cwd=repo, env=env).returncode


if __name__ == '__main__':
    raise SystemExit(main())
