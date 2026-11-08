#!/usr/bin/env python3
"""Exercise Ito's notice/native archive boundary and removed-check controls."""

import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
import warnings
import zipfile

# Host-only checks must not create Git-visible output beside their sources.
sys.dont_write_bytecode = True
from check_apk_notices import verify


class ApkNoticesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory(prefix="ito-notice-test-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.expected = self.root / "generated"
        notices = self.expected / "notices"
        notices.mkdir(parents=True)
        self.text = b"Copyright Example\nFull permission and warranty terms.\n"
        self.native = b"synthetic native binary binding"
        (notices / "LICENSE.txt").write_bytes(self.text)
        (notices / "manifest.json").write_text(json.dumps({
            "schema": 1, "artifacts": [{"coordinate": "example:library:1"}],
            "sourceComponents": [{"name": "example-native"}],
            "nativeLibraries": {f"lib/x86_64/{name}": hashlib.sha256(self.native).hexdigest()
                                for name in ("libyume.so", "libitocall.so", "libc++_shared.so")},
            "files": {"LICENSE.txt": {"sha256": hashlib.sha256(self.text).hexdigest()}},
        }) + "\n")
        self.apk = self.root / "candidate.apk"

    def write_apk(self, text: bytes | None, *, duplicate: bool = False, extra: bool = False,
                  native: bytes | None = None, omit_native: bool = False) -> None:
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("classes.dex", b"synthetic fixture")
            if not omit_native:
                for name in ("libyume.so", "libitocall.so", "libc++_shared.so"):
                    archive.writestr("lib/x86_64/" + name, native if native is not None else self.native)
            if text is not None:
                archive.writestr("assets/notices/manifest.json",
                                 (self.expected / "notices/manifest.json").read_bytes())
                archive.writestr("assets/notices/LICENSE.txt", text)
                if duplicate:
                    with warnings.catch_warnings():
                        warnings.simplefilter("ignore", UserWarning)
                        archive.writestr("assets/notices/LICENSE.txt", text)
                if extra:
                    archive.writestr("assets/notices/stale.txt", b"obsolete dependency")

    def test_complete_text_passes(self) -> None:
        self.write_apk(self.text)
        self.assertEqual(verify(self.apk, self.expected)["notice_entries"], 2)

    def test_notice_omission_fails(self) -> None:
        self.write_apk(None)
        with self.assertRaisesRegex(ValueError, "Notice inventory differs: missing="):
            verify(self.apk, self.expected)

    def test_truncated_text_fails(self) -> None:
        self.write_apk(self.text[:-1])
        with self.assertRaisesRegex(ValueError, "Incomplete or changed notice"):
            verify(self.apk, self.expected)

    def test_duplicate_text_fails(self) -> None:
        self.write_apk(self.text, duplicate=True)
        with self.assertRaisesRegex(ValueError, "Duplicate notice entries"):
            verify(self.apk, self.expected)

    def test_stale_notice_fails(self) -> None:
        self.write_apk(self.text, extra=True)
        with self.assertRaisesRegex(ValueError, "unexpected="):
            verify(self.apk, self.expected)

    def test_damaged_generated_source_fails(self) -> None:
        self.write_apk(self.text)
        (self.expected / "notices/LICENSE.txt").write_bytes(b"SPDX label only")
        with self.assertRaisesRegex(ValueError, "Generated notice hash differs"):
            verify(self.apk, self.expected)

    def test_native_library_substitution_fails(self) -> None:
        self.write_apk(self.text, native=b"different binary")
        with self.assertRaisesRegex(ValueError, "native library hash differs"):
            verify(self.apk, self.expected)

    def test_native_library_omission_fails(self) -> None:
        self.write_apk(self.text, omit_native=True)
        with self.assertRaisesRegex(ValueError, "Missing or duplicate packaged native"):
            verify(self.apk, self.expected)


if __name__ == "__main__":
    unittest.main()
