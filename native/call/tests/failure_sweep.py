"""Fresh-process, single-shot allocation sweeps. No Git or network operations."""
from __future__ import annotations
import json
import re
import os
import signal
import subprocess
import sys

def main() -> None:
    binary = sys.argv[1]
    cases = sys.argv[2:] or ('reserved', 'join', 'sign', 'tls-client', 'tls-server', 'context')
    subprocess.run([binary, 'observer', '0'], check=True, timeout=30)
    for case in cases:
        def run(index: int) -> dict[str, int]:
            child = subprocess.Popen([binary, case, str(index)], stdout=subprocess.PIPE,
                                     stderr=subprocess.PIPE, text=True, start_new_session=True)
            timed_out = False
            try:
                output, errors = child.communicate(timeout=20)
            except subprocess.TimeoutExpired:
                timed_out = True
                os.killpg(child.pid, signal.SIGKILL)
                output, errors = child.communicate(timeout=5)
            except BaseException:
                os.killpg(child.pid, signal.SIGKILL)
                child.wait(timeout=5)
                raise
            print(json.dumps({'child_case': case, 'index': index, 'pid': child.pid,
                              'actual_exit': child.returncode, 'timed_out': timed_out}), file=sys.stderr, flush=True)
            result = subprocess.CompletedProcess(child.args, child.returncode, output, errors)
            if timed_out:
                sys.stderr.write(f'{case} index={index} timed out after 20 seconds\n{output}{errors}')
                raise SystemExit(1)
            if result.returncode:
                sys.stderr.write(f'{case} index={index} exit={result.returncode}\n{result.stdout}{result.stderr}')
                raise SystemExit(1)
            match = re.fullmatch(rf'case={case} index=({index}) allocations=(\d+) hit=(\d+) freed=(\d+) clean=1\n', result.stdout)
            if match is None:
                raise RuntimeError('missing exact case receipt')
            row = dict(zip(('index', 'allocations', 'hit', 'freed'), map(int, match.groups())))
            print(json.dumps({'case': case, **row}), flush=True)
            return row
        baseline = run(0)
        if not 0 < baseline['allocations'] < 10000:
            raise RuntimeError('empty or unexpectedly large injection sweep')
        rows = [run(index) for index in range(1, baseline['allocations'] + 2)]
        if not all(row['hit'] == 1 for row in rows[:-1]) or rows[-1]['hit'] != 0:
            raise RuntimeError('allocation inventory changed between fresh processes')
        print(json.dumps({'case': case, 'baseline': baseline, 'injections': len(rows)-1,
                          'all_before_free_checks_clean': True, 'rows': rows, 'first_nonfailing_index': rows[-1]['index']}), flush=True)

if __name__ == '__main__':
    main()
