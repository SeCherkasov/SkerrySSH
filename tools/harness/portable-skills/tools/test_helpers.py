#!/usr/bin/env python3
"""Behavior checks for portable installation and resource-runner refusal paths."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path, PureWindowsPath
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


installer = module('skerry_install', ROOT / 'tools/install.py')
runner = module('skerry_bounded', ROOT / 'skills/skerry-new-machine/scripts/bounded_run.py')
inspector = module('skerry_inspect', ROOT / 'skills/skerry-new-machine/scripts/inspect_environment.py')
verifier = module('skerry_verify', ROOT / 'tools/verify_bundle.py')


class VerificationBehavior(unittest.TestCase):
    def test_utf8_bundle_verifies_with_non_utf8_locale_fallback(self):
        read_text = Path.read_text

        def locale_read(path, encoding=None, **kwargs):
            return read_text(path, encoding=encoding or 'ascii', **kwargs)

        with patch.object(Path, 'read_text', locale_read):
            result = verifier.validate(ROOT)
        self.assertEqual(result['errors'], [])

    def test_windows_paths_match_portable_manifest_names(self):
        root = PureWindowsPath('C:/development/skills')
        self.assertEqual(
            verifier.relative_name(root / 'skills/skerry-start/SKILL.md', root),
            'skills/skerry-start/SKILL.md',
        )


class InstallBehavior(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='skerry portable test ')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / 'source'
        self.destination = self.root / 'destination with spaces'
        for name in ['skerry-one', 'skerry-two']:
            p = self.source / name
            p.mkdir(parents=True)
            (p / 'SKILL.md').write_text(name)

    def test_dry_run_has_no_filesystem_mutations(self):
        self.assertEqual(len(installer.install(self.source, self.destination, True)), 2)
        self.assertFalse(self.destination.exists())

    def test_install_preserves_contents_at_paths_with_spaces(self):
        installer.install(self.source, self.destination)
        self.assertEqual((self.destination / 'skerry-two/SKILL.md').read_text(), 'skerry-two')
        self.assertEqual(len(list(self.destination.iterdir())), 2)

    def test_conflict_refuses_entire_install_and_preserves_existing(self):
        p = self.destination / 'skerry-two'
        p.mkdir(parents=True)
        (p / 'SKILL.md').write_text('USER CONTENT')
        with self.assertRaises(ValueError):
            installer.install(self.source, self.destination)
        self.assertEqual((p / 'SKILL.md').read_text(), 'USER CONTENT')
        self.assertFalse((self.destination / 'skerry-one').exists())

    def test_copy_failure_rolls_back_only_our_new_directories(self):
        self.destination.mkdir()
        preserved = self.destination / 'unrelated.txt'
        preserved.write_text('keep')
        with patch.object(installer.shutil, 'move', side_effect=OSError('simulated disk error')):
            with self.assertRaises(OSError):
                installer.install(self.source, self.destination)
        self.assertEqual(list(self.destination.iterdir()), [preserved])

    def test_symlink_is_not_followed_into_bundle(self):
        link = self.source / 'skerry-one/linked-secret'
        try:
            link.symlink_to(self.root / 'not-part-of-bundle')
        except OSError:
            self.skipTest('symlinks unavailable on this host')
        with self.assertRaises(ValueError):
            installer.install(self.source, self.destination)


class RunnerBehavior(unittest.TestCase):
    def test_refusal_never_launches_unbounded_command(self):
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp)
            (repo / 'tools/harness').mkdir(parents=True)
            (repo / 'tools/harness/gate.py').touch()
            with patch('sys.argv', ['bounded_run.py', '--repo', temp, '--', 'should-never-run']), \
                 patch.object(runner, 'preflight', return_value=['Less than 6 GiB MemAvailable']), \
                 patch.object(runner.subprocess, 'run') as launch, contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as result:
                    runner.main()
                self.assertEqual(result.exception.code, 2)
                launch.assert_not_called()

    def test_dry_run_preserves_argv_and_applies_group_limits_without_execution(self):
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp)
            (repo / 'tools/harness').mkdir(parents=True)
            (repo / 'tools/harness/gate.py').touch()
            out = io.StringIO()
            with patch('sys.argv', ['bounded_run.py', '--repo', temp, '--dry-run', '--', 'tool', 'argument with spaces']), \
                 patch.object(runner, 'preflight', return_value=[]), \
                 patch.object(runner.subprocess, 'run') as launch, contextlib.redirect_stdout(out):
                self.assertEqual(runner.main(), 0)
                launch.assert_not_called()
            plan = json.loads(out.getvalue())
            self.assertEqual(plan['command'][-2:], ['tool', 'argument with spaces'])
            for bound in ['MemoryMax=3G', 'MemorySwapMax=0', 'CPUQuota=200%']:
                self.assertIn(bound, plan['command'])


class InspectorBehavior(unittest.TestCase):
    def test_server_mode_omits_client_sdk_checks_and_does_not_launch_gradle(self):
        with tempfile.TemporaryDirectory() as temp:
            with patch.object(inspector, 'probe', return_value={'exit': 0, 'version': '21'} ) as probe:
                result = inspector.inspect(Path(temp), server_only=True)
            commands = [call.args[0] for call in probe.call_args_list]
            self.assertEqual(commands, [['git', '--version'], ['java', '-version'], ['javac', '-version']])
            self.assertFalse(any('android-sdk' == c['name'] for c in result['checks']))

    def test_java_11_is_reported_as_not_ready(self):
        with tempfile.TemporaryDirectory() as temp:
            with patch.object(inspector, 'probe', return_value={'exit': 0, 'version': 'openjdk version "11.0.22"'}):
                result = inspector.inspect(Path(temp), server_only=True)
            self.assertFalse(next(c['ok'] for c in result['checks'] if c['name'] == 'java 21'))


if __name__ == '__main__':
    unittest.main()
