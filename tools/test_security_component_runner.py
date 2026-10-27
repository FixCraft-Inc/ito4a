"""Evidence validation regressions. These tests do not compile cryptography."""
import copy
import unittest

import evaluate_security_component as evaluation


class EvidenceValidationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.spec = {'group': 'X25519MLKEM768', 'cipher': 'TLS_AES_256_GCM_SHA384'}
        self.result = {
            'component': 'OpenSSL', 'version': 'OpenSSL 3.5.9 29 Sep 2026',
            **self.spec, 'ok': True, 'count': 27, 'passed': 27,
            'cases': [{'name': name, 'passed': True} for name in sorted(evaluation.EXPECTED_CASES)],
        }

    def test_complete_reviewed_case_set(self) -> None:
        evaluation.validate_result(self.result, self.spec)

    def test_single_passing_case_cannot_qualify_component(self) -> None:
        self.result['cases'] = self.result['cases'][:1]
        self.result.update(count=1, passed=1)
        with self.assertRaises(ValueError):
            evaluation.validate_result(self.result, self.spec)

    def test_boolean_counts_are_not_integer_counts(self) -> None:
        self.result.update(count=True, passed=True)
        with self.assertRaises(ValueError):
            evaluation.validate_result(self.result, self.spec)

    def test_duplicate_case_cannot_replace_missing_case(self) -> None:
        self.result['cases'][-1] = copy.deepcopy(self.result['cases'][0])
        with self.assertRaises(ValueError):
            evaluation.validate_result(self.result, self.spec)

    def test_failed_or_untyped_case_is_refused(self) -> None:
        for value in (False, 1, 'true'):
            with self.subTest(value=value):
                self.result['cases'][0]['passed'] = value
                with self.assertRaises(ValueError):
                    evaluation.validate_result(self.result, self.spec)

    def test_profile_change_is_refused(self) -> None:
        self.result['group'] = 'X25519'
        with self.assertRaises(ValueError):
            evaluation.validate_result(self.result, self.spec)

    def test_unknown_root_field_is_refused(self) -> None:
        self.result['skipped'] = True
        with self.assertRaises(ValueError):
            evaluation.validate_result(self.result, self.spec)


if __name__ == '__main__':
    unittest.main()
