#!/usr/bin/env python3
# Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later.
"""Exercise process evidence and ownership without native builds or network."""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

import call_harness


class CallChildrenTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix='ito-call-harness-test-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.program = self.root / 'fake-call'
        self.program.write_text(f'#!{sys.executable}\n' + textwrap.dedent('''\
            import json
            import os
            import sys
            import time
            from pathlib import Path

            arguments = sys.argv[1:]
            def value(flag, default=None):
                return arguments[arguments.index(flag) + 1] if flag in arguments else default
            if '--invitation-out' in arguments:
                Path(value('--invitation-out')).write_bytes(b'private invitation')
            if '--hang' in arguments:
                time.sleep(300)
            if '--no-result' not in arguments:
                Path(value('--result')).write_text(json.dumps({'result': 'timeout'}))
            if '--stderr' in arguments:
                print(value('--stderr'), file=sys.stderr)
            if '--large-stderr' in arguments:
                sys.stderr.write('x' * 1048576)
            sys.exit(int(value('--exit', '0')))
            '''), encoding='utf-8')
        self.program.chmod(0o700)
        harness = SimpleNamespace(directories={'alice': self.root, 'bob': self.root})
        arguments = argparse.Namespace(ito_call=self.program, resolver=self.root / 'resolver')
        self.endpoints = call_harness.Endpoints(harness, arguments, self.root)
        self.endpoints.codes['bob'] = 'test-peer'
        self.children: list[subprocess.Popen] = []
        real_popen = subprocess.Popen

        def launch(*arguments, **options):
            child = real_popen(*arguments, **options)
            self.children.append(child)
            return child

        patch = mock.patch.object(call_harness.subprocess, 'Popen', side_effect=launch)
        patch.start()
        self.addCleanup(patch.stop)
        self.addCleanup(self.stop_children)

    def stop_children(self) -> None:
        for child in self.children:
            if child.poll() is None:
                child.kill()
            child.wait(timeout=5)

    def assert_reaped(self) -> None:
        for child in self.children:
            self.assertIsNotNone(child.returncode, 'owned child was left alive')
            with self.assertRaises(ChildProcessError):
                os.waitpid(child.pid, os.WNOHANG)

    def test_result_does_not_hide_nonzero_exit(self) -> None:
        for role in ('initiator', 'responder'):
            with self.subTest(role=role):
                with self.assertRaisesRegex(call_harness.Failure, f'{role} exited 9'):
                    self.endpoints.call('alice', 'bob', **{role + '_args': ['--exit', '9']})
                self.assert_reaped()

    def test_result_does_not_hide_sanitizer_diagnostic(self) -> None:
        for diagnostic in ('ERROR: AddressSanitizer: heap-use-after-free',
                           'ERROR: LeakSanitizer: detected memory leaks',
                           'source.cpp:8:3: runtime error: misaligned access'):
            with self.subTest(diagnostic=diagnostic):
                with self.assertRaisesRegex(call_harness.Failure, 'sanitizer diagnostic'):
                    self.endpoints.call('alice', 'bob',
                                        responder_args=['--stderr', diagnostic])
                self.assert_reaped()

    def test_typed_terminal_failure_with_zero_exit_is_valid(self) -> None:
        outcome = self.endpoints.call('alice', 'bob',
                                      responder_args=['--large-stderr'])
        self.assertEqual(outcome['initiator']['result'], 'timeout')
        self.assertEqual(outcome['responder']['result'], 'timeout')
        for log in self.endpoints.work.glob('*.stderr'):
            self.assertEqual(log.stat().st_mode & 0o777, 0o600)
        self.assert_reaped()

    def test_pre_result_typed_refusal_is_available_to_scenario(self) -> None:
        outcome = self.endpoints.call(
            'alice', 'bob', responder_args=['--no-result', '--exit', '2',
                                           '--stderr', 'ito-call: invitation: untrusted'])
        self.assertEqual(outcome['responder'],
                         {'exit': 2, 'error': 'ito-call: invitation: untrusted'})
        self.assert_reaped()

    def test_during_exception_reaps_both_children(self) -> None:
        def fail_during() -> None:
            self.assertEqual(len(self.children), 2)
            self.assertTrue(all(child.poll() is None for child in self.children))
            raise ValueError('injected during failure')

        with self.assertRaisesRegex(ValueError, 'injected during failure'):
            self.endpoints.call('alice', 'bob', initiator_args=['--hang'],
                                responder_args=['--hang'], during=fail_during)
        self.assert_reaped()
        self.assertEqual(list(self.endpoints.work.glob('*invitation')), [])

    def test_responder_setup_exception_reaps_initiator(self) -> None:
        current_popen = call_harness.subprocess.Popen

        def fail_responder(*arguments, **options):
            if self.children:
                raise OSError('injected responder spawn failure')
            return current_popen(*arguments, **options)

        with mock.patch.object(call_harness.subprocess, 'Popen', side_effect=fail_responder):
            with self.assertRaisesRegex(OSError, 'injected responder spawn failure'):
                self.endpoints.call('alice', 'bob', initiator_args=['--hang'])
        self.assertEqual(len(self.children), 1)
        self.assert_reaped()


if __name__ == '__main__':
    unittest.main()
