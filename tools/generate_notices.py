#!/usr/bin/env python3
"""Generate complete notices from reviewed sources and resolved runtime artifacts.

Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import sys
import tempfile
import zipfile


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def regular_bytes(path: Path) -> bytes:
    if path.is_symlink() or not path.is_file():
        raise ValueError(f"Expected a regular notice input: {path.name}")
    return path.read_bytes()


def provider_source(artifact: Path, coordinate: str, binding: dict) -> dict:
    if (set(binding) != {"version", "source_sha256"} or
            binding["version"] != "0.4.0" or
            not re.fullmatch(r"[0-9a-f]{64}", binding["source_sha256"]) or
            coordinate != f"com.fixcraft.yume:provider-library:{binding['version']}"):
        raise ValueError("Shared provider source binding is incomplete")
    with zipfile.ZipFile(artifact) as archive:
        names = archive.namelist()
        target = "assets/provider-library/provenance.json"
        if names.count(target) != 1 or archive.getinfo(target).file_size > 1024 * 1024:
            raise ValueError("Shared provider source provenance is missing or unbounded")
        provenance = json.loads(archive.read(target))
    if (provenance["schema"] != 1 or provenance["component"] != "com.fixcraft.yume:provider-library" or
            provenance["version"] != binding["version"] or
            provenance["source_sha256"] != binding["source_sha256"] or not provenance["files"]):
        raise ValueError("Shared provider source provenance differs")
    canonical = ""
    for name, entry in sorted(provenance["files"].items()):
        if (not re.fullmatch(r"[A-Za-z0-9._/-]+", name) or name.startswith("/") or
                any(part in (".", "..", "") for part in name.split("/")) or
                type(entry["mode"]) is not int or entry["mode"] not in range(0o1000) or
                not re.fullmatch(r"[0-9a-f]{64}", entry["sha256"])):
            raise ValueError("Shared provider source entry is invalid")
        canonical += f"{name}\0{entry['mode']}\0{entry['sha256']}\n"
    if sha256(canonical.encode()) != binding["source_sha256"]:
        raise ValueError("Shared provider source manifest is inconsistent")
    return binding


def call_core_libraries(catalog: dict, native: dict, call_path: Path) -> tuple[dict, dict]:
    """The call core's libraries, bound to its provenance and catalog pin."""
    call = json.loads(regular_bytes(call_path))
    if type(call.get("schema")) is not int or call["schema"] != 1:
        raise ValueError("Unsupported call core provenance schema")
    pin = catalog["callCore"]
    if call["openssl_archive_sha256"] != pin["openssl_archive_sha256"]:
        raise ValueError("Call core OpenSSL changed: notice review required")
    if call["ndk"] != catalog["ndk"] or sorted(call["libraries"]) != sorted(native["abis"]):
        raise ValueError("Call core provenance does not match the native build")
    libraries = {}
    for abi in native["abis"]:
        expected = call["libraries"][abi]
        if sha256(regular_bytes(call_path.parent / abi / "libitocall.so")) != expected:
            raise ValueError("Call core library differs from provenance")
        libraries[f"lib/{abi}/libitocall.so"] = expected
    identity = {"core_sources_sha256": call["core_sources_sha256"],
                "openssl_archive_sha256": call["openssl_archive_sha256"]}
    return libraries, identity


def generate(root: Path, yume_root: Path, catalog_path: Path,
             artifacts_path: Path, provenance_path: Path, output: Path,
             call_path: Path) -> dict:
    catalog = json.loads(regular_bytes(catalog_path))
    resolved = json.loads(regular_bytes(artifacts_path))
    native = json.loads(regular_bytes(provenance_path))
    if (type(catalog["schema"]) is not int or catalog["schema"] != 1 or
            type(native["schema"]) is not int or native["schema"] != 1):
        raise ValueError("Unsupported notice/provenance schema")
    if native["android_api"] != 28 or not native["abis"]:
        raise ValueError("Native Android provenance is incomplete")
    revision = "Pkg.Revision = " + catalog["ndk"]
    if revision not in native["ndk_source_properties"].splitlines():
        raise ValueError("NDK license review required")
    for relative, expected in catalog["nativeGuards"].items():
        if sha256(regular_bytes(yume_root / relative)) != expected:
            raise ValueError(f"Native license input changed: {relative}")
    if not resolved["artifacts"] or len(resolved["artifacts"]) > 512:
        raise ValueError("Missing or excessive runtime dependency inventory")
    providers_enabled = resolved.get("providersEnabled", True)
    if not isinstance(providers_enabled, bool):
        raise ValueError("Invalid provider feature selection")
    if not providers_enabled and "providerIdentity" in resolved:
        raise ValueError("Minimal build contains provider identity")
    source_components = [component for component in catalog["sourceComponents"]
                         if providers_enabled or component["name"] != "YUME shared Android provider library"]
    selected = set(catalog["common"])
    artifacts = []
    coordinates = set()
    provider_identity = None
    for artifact in sorted(resolved["artifacts"], key=lambda entry: entry["coordinate"]):
        coordinate = artifact["coordinate"]
        if not isinstance(coordinate, str) or coordinate in coordinates:
            raise ValueError("Invalid or duplicate runtime artifact coordinate")
        coordinates.add(coordinate)
        if not providers_enabled and (coordinate.startswith("com.fixcraft.yume:provider-library:") or
                                      coordinate.startswith("net.openid:appauth:")):
            raise ValueError("Minimal build contains provider dependency")
        path = Path(artifact["path"])
        known = catalog["artifacts"].get(coordinate)
        if coordinate.startswith("com.fixcraft.yume:provider-library:"):
            if provider_identity is not None:
                raise ValueError("Multiple shared provider source artifacts")
            provider_identity = provider_source(path, coordinate, resolved.get("providerIdentity", {}))
            known = {"sha256": sha256(regular_bytes(path)), "notices": ["LICENSE"]}
        if known is None:
            raise ValueError(f"Dependency notice review required: {coordinate}")
        digest = sha256(regular_bytes(path))
        if digest != known["sha256"] or not known["notices"]:
            raise ValueError(f"Dependency notice review required: {coordinate}")
        selected.update(known["notices"])
        artifacts.append({"coordinate": coordinate, "sha256": digest, "notices": known["notices"]})
    if not catalog["sourceComponents"]:
        raise ValueError("Missing native/application source components")
    if (any(component["name"] == "YUME shared Android provider library" for component in source_components)
            and provider_identity is None):
        raise ValueError("Missing shared provider source artifact")
    for component in source_components:
        if not component["notices"] or not set(component["notices"]).issubset(selected):
            raise ValueError("Source component notice selection is incomplete")
    files = {}
    payloads = {}
    for name in sorted(selected):
        if not re.fullmatch(r"[A-Za-z0-9_.-]+", name) or name in (".", "..", "manifest.json"):
            raise ValueError("Invalid notice asset name")
        source = catalog["sources"][name]
        path = root / source["path"]
        data = regular_bytes(path)
        if sha256(data) != source["sha256"]:
            raise ValueError(f"Notice source changed: {name}")
        payloads[name] = data
        files[name] = {"sha256": source["sha256"], "origin": source["origin"]}
    native_libraries = {}
    triplets = {"arm64-v8a": "aarch64-linux-android", "armeabi-v7a": "arm-linux-androideabi",
                "x86": "i686-linux-android", "x86_64": "x86_64-linux-android"}
    ndk = Path(native["ndk"])
    if (ndk / "source.properties").read_text() != native["ndk_source_properties"]:
        raise ValueError("NDK provenance changed")
    for abi in native["abis"]:
        if abi not in triplets:
            raise ValueError("Unsupported native ABI")
        library = f"{abi}/libyume.so"
        expected = native["artifacts"][library]
        if sha256(regular_bytes(provenance_path.parent / library)) != expected["sha256"]:
            raise ValueError("Native library differs from provenance")
        native_libraries[f"lib/{library}"] = expected["sha256"]
        runtime = ndk / "toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib" / triplets[abi] / "libc++_shared.so"
        native_libraries[f"lib/{abi}/libc++_shared.so"] = sha256(regular_bytes(runtime))
    call_libraries, call_identity = call_core_libraries(catalog, native, call_path)
    native_libraries.update(call_libraries)
    manifest = {"schema": 1, "callIdentity": call_identity, "nativeIdentity": {
        "head": native["head"], "inputs_sha256": native["inputs_sha256"],
        "unverified_test_source": native["unverified_test_source"], "ndk": catalog["ndk"],
    }, "nativeLibraries": native_libraries, "sourceComponents": source_components, "providersEnabled": providers_enabled,
        "artifacts": artifacts, "files": files}
    if provider_identity is not None:
        manifest["providerIdentity"] = provider_identity
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.is_symlink():
        raise ValueError("Notice output must not be a symlink")
    with tempfile.TemporaryDirectory(prefix=".ito-notices-", dir=output.parent) as temporary:
        staged = Path(temporary) / "notices"
        staged.mkdir()
        for name, data in payloads.items():
            (staged / name).write_bytes(data)
        (staged / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        # One Gradle task owns this generated directory. Replace only its notices.
        output.mkdir(exist_ok=True)
        target = output / "notices"
        if target.is_symlink():
            raise ValueError("Notice output must not be a symlink")
        if target.exists():
            shutil.rmtree(target)
        staged.replace(target)
    return {"artifacts": len(artifacts), "notice_files": len(files),
            "native_libraries": len(native_libraries), "output": str(output)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--yume-root", required=True, type=Path)
    parser.add_argument("--catalog", required=True, type=Path)
    parser.add_argument("--artifacts", required=True, type=Path)
    parser.add_argument("--native-provenance", required=True, type=Path)
    parser.add_argument("--call-provenance", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        result = generate(args.root, args.yume_root, args.catalog, args.artifacts,
                          args.native_provenance, args.output, args.call_provenance)
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
        print(f"Notice generation failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
