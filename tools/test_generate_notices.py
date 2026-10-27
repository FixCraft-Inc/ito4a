#!/usr/bin/env python3
"""Check refusal and publication boundaries of Ito's notice generator."""

import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

sys.dont_write_bytecode = True
from generate_notices import generate


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class NoticeGenerationTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory(prefix="ito-generate-notices-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.yume = self.root / "yume"
        self.yume.mkdir()
        self.guard = self.yume / "dependencies.json"
        self.guard.write_bytes(b"native pin\n")
        self.license = self.root / "LICENSE"
        self.license.write_bytes(b"Complete example license terms\n")
        self.artifact = self.root / "library.jar"
        self.artifact.write_bytes(b"resolved runtime bytes")
        self.catalog = self.root / "catalog.json"
        self.resolved = self.root / "runtime.json"
        self.native = self.root / "native"
        (self.native / "x86_64").mkdir(parents=True)
        (self.native / "x86_64/libyume.so").write_bytes(b"core binary")
        self.ndk = self.root / "ndk"
        runtime = self.ndk / "toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/x86_64-linux-android/libc++_shared.so"
        runtime.parent.mkdir(parents=True)
        runtime.write_bytes(b"C++ runtime binary")
        self.properties = "Pkg.Revision = 28.2.13676358\n"
        (self.ndk / "source.properties").write_text(self.properties)
        self.provenance = self.native / "provenance.json"
        self.output = self.root / "generated"
        self.catalog_value = {
            "schema": 1, "ndk": "28.2.13676358", "nativeGuards": {
                "dependencies.json": digest(self.guard.read_bytes())},
            "common": ["LICENSE"], "sourceComponents": [{"name": "example", "notices": ["LICENSE"]}],
            "sources": {"LICENSE": {"path": "LICENSE", "sha256": digest(self.license.read_bytes()),
                                      "origin": "synthetic license fixture"}},
            "artifacts": {"example:library:1": {"sha256": digest(self.artifact.read_bytes()),
                                                  "notices": ["LICENSE"]}},
        }
        self.catalog.write_text(json.dumps(self.catalog_value))
        self.resolved.write_text(json.dumps({"artifacts": [
            {"coordinate": "example:library:1", "path": str(self.artifact)}]}))
        self.provenance.write_text(json.dumps({
            "schema": 1, "android_api": 28, "abis": ["x86_64"], "ndk": str(self.ndk),
            "ndk_source_properties": self.properties, "head": "fixture-head",
            "inputs_sha256": "fixture-input-binding", "unverified_test_source": True,
            "artifacts": {"x86_64/libyume.so": {"sha256": digest(b"core binary")}},
        }))

    def run_generator(self) -> dict:
        return generate(self.root, self.yume, self.catalog, self.resolved, self.provenance, self.output)

    def add_provider(self, *, altered_entry: bool = False, wrong_binding: bool = False) -> None:
        entry = {"mode": 0o644, "sha256": digest(b"provider source"), "size": 15}
        source = digest(f"src/main/Provider.kt\0{entry['mode']}\0{entry['sha256']}\n".encode())
        provenance = {"schema": 1, "component": "com.fixcraft.yume:provider-library", "version": "0.1.0",
                      "source_sha256": source, "files": {"src/main/Provider.kt": entry}}
        if altered_entry:
            entry["sha256"] = "f" * 64
        artifact = self.root / "provider-library.aar"
        with zipfile.ZipFile(artifact, "w") as archive:
            archive.writestr("assets/provider-library/provenance.json", json.dumps(provenance))
        resolved = json.loads(self.resolved.read_text())
        resolved["artifacts"].append({"coordinate": "com.fixcraft.yume:provider-library:0.1.0", "path": str(artifact)})
        resolved["providerIdentity"] = {"version": "0.1.0", "source_sha256": "e" * 64 if wrong_binding else source}
        self.resolved.write_text(json.dumps(resolved))

    def test_provider_source_aar_keeps_exact_runtime_hash_and_source_binding(self) -> None:
        self.add_provider()
        self.run_generator()
        result = json.loads((self.output / "notices/manifest.json").read_text())
        self.assertEqual(result["providerIdentity"]["version"], "0.1.0")
        provider = next(item for item in result["artifacts"] if item["coordinate"].startswith("com.fixcraft.yume:"))
        self.assertEqual(provider["sha256"], digest((self.root / "provider-library.aar").read_bytes()))

    def test_provider_source_substitution_refuses(self) -> None:
        self.add_provider(wrong_binding=True)
        with self.assertRaisesRegex(ValueError, "provenance differs"):
            self.run_generator()

    def test_inconsistent_provider_manifest_refuses(self) -> None:
        self.add_provider(altered_entry=True)
        with self.assertRaisesRegex(ValueError, "manifest is inconsistent"):
            self.run_generator()

    def test_complete_inventory_and_replacement(self) -> None:
        result = self.run_generator()
        self.assertEqual(result["artifacts"], 1)
        self.assertEqual(result["native_libraries"], 2)
        (self.output / "notices/stale.txt").write_bytes(b"stale")
        self.run_generator()
        self.assertFalse((self.output / "notices/stale.txt").exists())
        self.assertEqual((self.output / "notices/LICENSE").read_bytes(), self.license.read_bytes())

    def test_unknown_runtime_refuses_publication(self) -> None:
        self.resolved.write_text(json.dumps({"artifacts": [
            {"coordinate": "example:library:2", "path": str(self.artifact)}]}))
        with self.assertRaisesRegex(ValueError, "Dependency notice review required"):
            self.run_generator()
        self.assertFalse(self.output.exists())

    def test_changed_runtime_preserves_previous_output(self) -> None:
        self.run_generator()
        previous = (self.output / "notices/manifest.json").read_bytes()
        self.artifact.write_bytes(b"substituted runtime")
        with self.assertRaisesRegex(ValueError, "Dependency notice review required"):
            self.run_generator()
        self.assertEqual((self.output / "notices/manifest.json").read_bytes(), previous)

    def test_changed_license_refuses(self) -> None:
        self.license.write_bytes(b"SPDX label only")
        with self.assertRaisesRegex(ValueError, "Notice source changed"):
            self.run_generator()

    def test_changed_native_pin_refuses(self) -> None:
        self.guard.write_bytes(b"another dependency pin")
        with self.assertRaisesRegex(ValueError, "Native license input changed"):
            self.run_generator()

    def test_changed_native_library_refuses(self) -> None:
        (self.native / "x86_64/libyume.so").write_bytes(b"another core binary")
        with self.assertRaisesRegex(ValueError, "Native library differs from provenance"):
            self.run_generator()

    def test_changed_ndk_refuses(self) -> None:
        (self.ndk / "source.properties").write_text("Pkg.Revision = 0\n")
        with self.assertRaisesRegex(ValueError, "NDK provenance changed"):
            self.run_generator()

    def test_symlink_license_refuses(self) -> None:
        target = self.root / "real-license"
        self.license.rename(target)
        self.license.symlink_to(target)
        with self.assertRaisesRegex(ValueError, "Expected a regular notice input"):
            self.run_generator()


if __name__ == "__main__":
    unittest.main()
