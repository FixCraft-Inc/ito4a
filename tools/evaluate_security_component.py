"""Build and run the bounded component probe against an explicit static prefix.

This is not an application endpoint, invitation parser or media implementation.
Build the pinned stock OpenSSL separately on the approved build host first.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
from typing import Sequence

EXPECTED_CASES = frozenset({
    'exact_component_version', 'sha256_abc_known_answer',
    'rfc8032_ed25519_test1_known_answer',
    'exact_psk_dhe_handshake_and_matching_exporters',
    'fresh_handshakes_have_distinct_exporters', 'bidirectional_encrypted_payload',
    'keyupdate_preserves_payload_and_channel_binding', 'wrong_psk_rejected',
    'missing_psk_rejected', 'wrong_psk_identity_rejected',
    'incompatible_psk_cipher_hash_rejected', 'tls12_rejected',
    'incompatible_dhe_group_rejected', 'classical_only_dhe_rejected_by_exact_policy',
    'psk_only_key_exchange_rejected_by_exact_policy', 'certificate_fallback_rejected',
    'corrupted_ciphertext_decrypt_fails_without_plaintext',
    'replayed_ciphertext_decrypt_fails_without_plaintext',
    'reordered_ciphertext_decrypt_fails_without_plaintext',
    'bilateral_application_identity_signatures', 'wrong_peer_signing_key_rejected',
    'changed_authentication_domain_rejected', 'changed_role_rejected',
    'changed_exporter_rejected', 'changed_context_digest_rejected',
    'changed_signature_rejected', 'signature_from_another_tls_channel_rejected',
})


def validate_result(result: object, spec: dict) -> None:
    """Reject partial, malformed or wrong-profile evidence, even with exit zero."""
    keys = {'component', 'version', 'group', 'cipher', 'cases', 'count', 'passed', 'ok'}
    if type(result) is not dict or set(result) != keys:
        raise ValueError('component result has an invalid root schema')
    if (result['component'] != 'OpenSSL' or
            result['version'] != 'OpenSSL 3.5.9 29 Sep 2026' or
            result['group'] != spec['group'] or result['cipher'] != spec['cipher']):
        raise ValueError('component result has a different version or profile')
    cases = result['cases']
    if type(cases) is not list or not cases:
        raise ValueError('component result has no case list')
    names = []
    for case in cases:
        if (type(case) is not dict or set(case) != {'name', 'passed'} or
                type(case['name']) is not str or case['passed'] is not True):
            raise ValueError('component result has a malformed or failed case')
        names.append(case['name'])
    if len(names) != len(EXPECTED_CASES) or set(names) != EXPECTED_CASES:
        raise ValueError('component result has an incomplete or duplicate case set')
    if (type(result['count']) is not int or type(result['passed']) is not int or
            result['count'] != len(cases) or result['passed'] != len(cases) or result['ok'] is not True):
        raise ValueError('component result has invalid counts or status')


def sha256(path: Path) -> str:
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def run(command: Sequence[str], log: Path, timeout: int) -> None:
    with log.open('xb') as output:
        process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT,
                                   start_new_session=True)
        try:
            code = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
            raise RuntimeError(f'{log.name}: deadline exceeded') from None
    if code != 0:
        raise RuntimeError(f'{log.name}: command exited {code}')


def evaluate(prefix: Path, output: Path, compiler: Path) -> None:
    prefix = prefix.resolve(strict=True)
    compiler = compiler.resolve(strict=True)
    if not compiler.is_file() or not os.access(compiler, os.X_OK):
        raise ValueError('compiler must be an executable file')
    metadata = Path(__file__).with_name('security-component.json')
    spec = json.loads(metadata.read_bytes())
    if spec['schema'] != 1 or spec['version'] != '3.5.9':
        raise ValueError('unsupported evaluation component')
    source = Path(__file__).with_name('security_component_eval.cpp')
    archives = [prefix / 'lib/libssl.a', prefix / 'lib/libcrypto.a']
    if not all(path.is_file() for path in [source, prefix / 'include/openssl/ssl.h', *archives]):
        raise ValueError('explicit prefix must contain headers and static archives under lib/')
    output.mkdir(mode=0o700)
    headers = sorted((prefix / 'include').rglob('*.h'))
    runner = Path(__file__).resolve(strict=True)
    interpreter = Path(sys.executable).resolve(strict=True)
    inputs = {str(path): sha256(path) for path in [
        source, metadata, runner, interpreter, compiler, *archives, *headers]}
    binary = output / 'security-component-eval'
    command = [str(compiler), '-std=c++20', '-O2', '-g', '-Wall', '-Wextra', '-Werror',
               '-Wpedantic', '-fvisibility=hidden', '-I', str(prefix / 'include'),
               str(source), *map(str, archives), '-ldl', '-pthread', '-o', str(binary)]
    run(command, output / 'compile.log', 120)
    run([str(binary)], output / 'cases.json', 30)
    result = json.loads((output / 'cases.json').read_bytes())
    validate_result(result, spec)
    run([str(compiler), '--version'], output / 'compiler-version.txt', 10)
    run([str(compiler), '-dumpmachine'], output / 'compiler-target.txt', 10)
    for path, digest in inputs.items():
        if sha256(Path(path)) != digest:
            raise RuntimeError('evaluation input changed during execution')
    (output / 'binding.json').write_text(json.dumps({
        'passed': True, 'inputs': inputs, 'component': spec,
        'compile_argv': command, 'binary_sha256': sha256(binary),
        'python_version': sys.version, 'python_executable': str(interpreter),
        'compiler_version_sha256': sha256(output / 'compiler-version.txt'),
        'compiler_target_sha256': sha256(output / 'compiler-target.txt'),
        'cases_sha256': sha256(output / 'cases.json'), 'case_count': len(result['cases']),
        'limits': 'component only; no app parser, storage, relay, Android runtime or audio',
    }, indent=2) + '\n')


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--openssl-prefix', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--compiler', required=True, type=Path)
    args = parser.parse_args()
    try:
        evaluate(args.openssl_prefix, args.output, args.compiler)
    except (OSError, ValueError, RuntimeError, KeyError, subprocess.SubprocessError) as error:
        print(f'evaluation failed: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
