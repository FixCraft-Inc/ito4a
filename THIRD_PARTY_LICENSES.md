# Dependency licenses and notices

Ito's original application and JNI adapter use AGPL-3.0-or-later, copyright
2026 FixCraft Inc. [LICENSE](LICENSE) contains the complete terms.
Third-party code retains its respective grants and attributions.

Every built APK carries complete selected texts under `assets/notices/`.
Its `manifest.json` records the actual resolved runtime coordinates and
artifact hashes, upstream notice origins, native source binding and packaged
libyume/C++ runtime hashes. `notices/catalog.json` is the reviewed packaging
source. A changed or unknown artifact or native license input fails the build
until its notices are reviewed. Debug instrumentation dependencies are test
inputs and are not represented as the application's runtime classpath.

The shared YUME Android provider library uses AGPL-3.0-or-later. Its source
version and full source aggregate are recorded in the notice manifest's
`providerIdentity` and `assets/provider-library/provenance.json`. The generator
checks the resolved source AAR's provenance and records its exact AAR hash.
AppAuth for Android 0.11.1 uses Apache-2.0. Its complete upstream license is
packaged as `AppAuth-LICENSE.txt` alongside the reviewed coroutines texts.
Its AndroidX browser, AppCompat, fragment and widget dependencies retain their
resolved versions. Their exact Google Maven artifacts and Apache-2.0 grants
are reviewed in the catalog, without changing versions to match another app.

## Native transport

Ito links YUME through its public C ABI. YUME and its OpenSSL patches use
AGPL-3.0-or-later. The common full AGPL text is packaged once as `LICENSE`.
The native build and exact source/library binding are described in
[NATIVE.md](docs/NATIVE.md). The transport library statically includes:

| Component | License | Reviewed version |
| --- | --- | --- |
| OpenSSL with YUME patches | Apache-2.0, plus AGPL-3.0-or-later for the patches | 3.5.7 |
| Boost networking and header dependencies | BSL-1.0 | 1.90.0 |
| nghttp2 | MIT | 1.68.0 |
| zlib | Zlib | 1.3.1 |
| Zstandard | BSD-3-Clause option, plus incorporated MIT notices | 1.5.7 |
| Brotli | MIT | 1.2.0 |
| nlohmann/json | MIT, plus incorporated CC0 notices | 3.11.3 |

The catalog guards YUME's dependency pins, Android build script, patched
OpenSSL source selection and nlohmann/json provenance. It preserves the
UTF-8 decoder, Grisu2 and Hedley attributions, the full Hedley CC0 text, and
Zstandard's xxHash/divsufsort source notices. These accompany the parent
library texts.

The separate `libc++_shared.so` comes from NDK 28.2.13676358. The APK includes
the complete LLVM notice with its exceptions and incorporated notices.
AndroidX may package `libandroidx.graphics.path.so` under Apache-2.0, covered
by its runtime artifact notice. BaseFWX is not part of Ito's native transport
or app-data storage.

## Android application libraries

The separate host-only [security component evaluation](docs/SECURITY_COMPONENT.md)
pins stock OpenSSL 3.5.9 under Apache-2.0. It changes no APK dependency or
packaged notice. Later endpoint integration must explicitly bind and include
its actual native archives, license/NOTICE inputs and corresponding source,
independently of libyume's existing patched OpenSSL inventory.

AndroidX, Compose, Material, Kotlin, coroutines, serialization and resolved
annotation/support libraries use the full Apache-2.0 texts and their applicable
upstream notices. Kotlin's NOTICE is included. The exact selected versions
and artifact hashes come from Gradle's runtime classpath, rather than this
summary or the set of reviewed-but-unused catalog entries. Debug tooling is
selected only for debug runtime inventories.

DataStore 1.1.7 repackages Protocol Buffers lite in
`androidx.datastore:datastore-preferences-external-protobuf`. That artifact's
published license is BSD-3-Clause, and its full Google copyright, grant and
disclaimer are selected separately. It is not covered by the general AndroidX
Apache attribution. Okio 3.4.0 uses Apache-2.0. Kotlin 1.8.0 compatibility
artifacts and 1.9.22 parcelize/extension runtimes retain their versioned NOTICE
texts alongside the selected Kotlin standard library's notice.

Complete upstream text copies under `notices/texts/` retain their original
bytes and recorded SHA-256/origin. Identical full license texts have one
canonical source. The generator does not depend on yume4a's application or
private store. It records no provider registrations or credentials.

## Corresponding source

YUME's public source is at <https://github.com/FixCraft-Inc/yume>. Its public
Android builder, dependency pins and patch series, together with Ito's Gradle
and JNI build definitions, identify the native composition. Source bindings
must accompany the exact distributed candidate.

A distributor must supply the corresponding source, modifications and
build/install material required by each applicable license. An upstream URL,
SPDX label or packaged notice alone does not supply that material. Dirty-native
debug APKs are qualification builds. Signing and distribution readiness are
separate gates.

## Archive check

On the build host, after building a variant:

```sh
python3 tools/check_apk_notices.py \
  --expected-dir app/build/generated/dependency-notices/debug \
  app/build/outputs/apk/debug/app-debug.apk
python3 tools/test_apk_notices.py
```

The checker compares every generated notice byte with the APK and rejects
missing, changed, truncated, duplicate or unexpected notice entries. It also
compares packaged libyume and libc++ bytes with their recorded build hashes.
Run the check for every selected variant/ABI output. Generated assets have
one build-task owner and are not hand-edited.
