#!/usr/bin/env python3
"""Check refusal and publication boundaries of Ito's notice generator."""

import hashlib
import json
from pathlib import Path
import sys
import subprocess
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
        self.call = self.root / "call"
        (self.call / "x86_64").mkdir(parents=True)
        (self.call / "x86_64/libitocall.so").write_bytes(b"call core binary")
        self.call_provenance = self.call / "provenance.json"
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
            "callCore": {"openssl_archive_sha256": "pinned-archive"},
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
        self.write_call_provenance()

    def write_call_provenance(self, archive: str = "pinned-archive") -> None:
        self.call_provenance.write_text(json.dumps({
            "schema": 1, "ndk": "28.2.13676358", "openssl_archive_sha256": archive,
            "head": "fixture-core-head", "unverified_test_source": False,
            "core_sources_sha256": "fixture-core-sources",
            "libraries": {"x86_64": digest(b"call core binary")},
        }))

    def run_generator(self) -> dict:
        return generate(self.root, self.yume, self.catalog, self.resolved, self.provenance, self.output,
                        self.call_provenance)

    def add_provider(self, *, altered_entry: bool = False, wrong_binding: bool = False) -> None:
        entry = {"mode": 0o644, "sha256": digest(b"provider source"), "size": 15}
        source = digest(f"src/main/Provider.kt\0{entry['mode']}\0{entry['sha256']}\n".encode())
        provenance = {"schema": 1, "component": "com.fixcraft.yume:provider-library", "version": "0.4.0",
                      "source_sha256": source, "files": {"src/main/Provider.kt": entry}}
        if altered_entry:
            entry["sha256"] = "f" * 64
        artifact = self.root / "provider-library.aar"
        with zipfile.ZipFile(artifact, "w") as archive:
            archive.writestr("assets/provider-library/provenance.json", json.dumps(provenance))
        resolved = json.loads(self.resolved.read_text())
        resolved["artifacts"].append({"coordinate": "com.fixcraft.yume:provider-library:0.4.0", "path": str(artifact)})
        resolved["providerIdentity"] = {"version": "0.4.0", "source_sha256": "e" * 64 if wrong_binding else source}
        self.resolved.write_text(json.dumps(resolved))

    def test_provider_source_aar_keeps_exact_runtime_hash_and_source_binding(self) -> None:
        self.add_provider()
        self.run_generator()
        result = json.loads((self.output / "notices/manifest.json").read_text())
        self.assertEqual(result["providerIdentity"]["version"], "0.4.0")
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

    def test_minimal_inventory_omits_provider_source_claim(self) -> None:
        self.catalog_value["sourceComponents"].append({
            "name": "YUME shared Android provider library", "notices": ["LICENSE"]})
        self.catalog.write_text(json.dumps(self.catalog_value))
        resolved = json.loads(self.resolved.read_text())
        resolved["providersEnabled"] = False
        self.resolved.write_text(json.dumps(resolved))
        self.run_generator()
        result = json.loads((self.output / "notices/manifest.json").read_text())
        self.assertFalse(result["providersEnabled"])
        self.assertNotIn("providerIdentity", result)
        self.assertEqual([component["name"] for component in result["sourceComponents"]], ["example"])

    def test_minimal_inventory_refuses_hidden_provider_dependency(self) -> None:
        coordinate = "net.openid:appauth:0.11.1"
        self.catalog_value["artifacts"][coordinate] = {
            "sha256": digest(self.artifact.read_bytes()), "notices": ["LICENSE"]}
        self.catalog.write_text(json.dumps(self.catalog_value))
        resolved = json.loads(self.resolved.read_text())
        resolved["providersEnabled"] = False
        resolved["artifacts"].append({"coordinate": coordinate, "path": str(self.artifact)})
        self.resolved.write_text(json.dumps(resolved))
        with self.assertRaisesRegex(ValueError, "Minimal build contains provider dependency"):
            self.run_generator()
        self.assertFalse(self.output.exists())

    def test_enabled_inventory_requires_advertised_provider(self) -> None:
        self.catalog_value["sourceComponents"].append({
            "name": "YUME shared Android provider library", "notices": ["LICENSE"]})
        self.catalog.write_text(json.dumps(self.catalog_value))
        resolved = json.loads(self.resolved.read_text())
        resolved["providersEnabled"] = True
        self.resolved.write_text(json.dumps(resolved))
        with self.assertRaisesRegex(ValueError, "Missing shared provider source artifact"):
            self.run_generator()

    def test_complete_inventory_and_replacement(self) -> None:
        result = self.run_generator()
        self.assertEqual(result["artifacts"], 1)
        self.assertEqual(result["native_libraries"], 3)  # libyume, libitocall, libc++_shared
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

    def test_call_core_library_is_bound(self) -> None:
        self.run_generator()
        manifest = json.loads((self.output / "notices/manifest.json").read_text())
        self.assertEqual(manifest["nativeLibraries"]["lib/x86_64/libitocall.so"], digest(b"call core binary"))
        self.assertEqual(manifest["callIdentity"]["openssl_archive_sha256"], "pinned-archive")

    def test_call_core_commit_is_recorded(self) -> None:
        self.run_generator()
        identity = json.loads((self.output / "notices/manifest.json").read_text())["callIdentity"]
        self.assertEqual(identity["head"], "fixture-core-head")
        self.assertIs(identity["unverified_test_source"], False)

    def test_call_core_without_a_commit_refuses(self) -> None:
        record = json.loads(self.call_provenance.read_text())
        del record["head"]
        self.call_provenance.write_text(json.dumps(record))
        with self.assertRaisesRegex(ValueError, "no source identity"):
            self.run_generator()

    def fake_strip(self, body: str) -> Path:
        tool = self.root / "fake-strip"
        tool.write_text("#!/bin/sh\n" + body)
        tool.chmod(0o755)
        return tool

    def test_release_inventory_hashes_the_stripped_bytes(self) -> None:
        # Arguments are --strip-unneeded -o OUTPUT INPUT, as AGP runs the NDK's tool.
        tool = self.fake_strip('[ "$1" = --strip-unneeded ] && [ "$2" = -o ] && { cat "$4"; printf stripped; } > "$3"\n')
        generate(self.root, self.yume, self.catalog, self.resolved, self.provenance, self.output, self.call_provenance, tool)
        libraries = json.loads((self.output / "notices/manifest.json").read_text())["nativeLibraries"]
        self.assertEqual(libraries["lib/x86_64/libyume.so"], digest(b"core binarystripped"))
        self.assertEqual(libraries["lib/x86_64/libitocall.so"], digest(b"call core binarystripped"))
        self.assertEqual(libraries["lib/x86_64/libc++_shared.so"], digest(b"C++ runtime binarystripped"))

    def test_release_still_refuses_a_library_that_differs_from_provenance(self) -> None:
        (self.native / "x86_64/libyume.so").write_bytes(b"swapped")
        tool = self.fake_strip('cat "$4" > "$3"\n')
        with self.assertRaises(ValueError):
            generate(self.root, self.yume, self.catalog, self.resolved, self.provenance, self.output, self.call_provenance, tool)

    def test_failed_strip_refuses(self) -> None:
        tool = self.fake_strip("exit 3\n")
        with self.assertRaises(subprocess.CalledProcessError):
            generate(self.root, self.yume, self.catalog, self.resolved, self.provenance, self.output, self.call_provenance, tool)

    def test_changed_call_library_refuses(self) -> None:
        (self.call / "x86_64/libitocall.so").write_bytes(b"other call binary")
        with self.assertRaisesRegex(ValueError, "Call core library differs from provenance"):
            self.run_generator()

    def test_changed_call_openssl_refuses(self) -> None:
        self.write_call_provenance("another-archive")
        with self.assertRaisesRegex(ValueError, "notice review required"):
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
