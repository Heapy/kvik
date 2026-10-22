# Native dependency commands

The composed Kotlin/Native executable now exposes `resolve` and `check-lock`. The bootstrap source-pins the bounded resolution and streaming tool-hash cinterop recipes, builds them through owned offline cinterop processes, and links production resolver/tool-verifier/compiler-adapter sources into the executable. Consumer build/test coordination is still pending.

`inspect`, resolution and locked checks share effective configuration identity, including explicit alignment pins. Online resolution publishes a canonical v1 dependency lock after CAS acquisition. Lock checking replays the metadata closure and verifies all referenced blobs without a transport or writable store. Whitespace changes in a lock preserve its canonical identity; concurrent-change detection compares the exact bytes observed. Both command-local and launcher-level offline flags disable resolution and repair.

Lock publication stages and fsyncs bytes, renames atomically, then syncs the destination directory. A persistent directory-wide mutex coordinates separate project roots and case aliases of a shared destination. Under that mutex, publication rechecks the original lock digest and project bytes. Inode checks reject project hardlink/case aliases and cache-directory aliases; the coordination filename is reserved. These checks assume trusted, stable directory ancestors, not an adversarial filesystem writer. Ordinary concurrent content changes fail with `KVIK_INPUT_CHANGED`, exit 3. A check is evidence at verification time, not a guarantee against subsequent edits.

Validated composition: `9d66ef4617bd230073780a5949a5d9ce772cd693446c2393842302e2e1179677` on the pinned macOS ARM64 host. Native composition construction reported 80,960 ms under concurrent development work; this is not an isolated startup benchmark.

Commands:

```sh
.kvik/schema-tools/bin/python tests/bootstrap/test_dependency_cli.py
.kvik/schema-tools/bin/python tests/bootstrap/test_inspection.py
python3 tests/bootstrap/test_worker_bundle.py --java-home "$JDK"
```

Results: seven real CLI tests passed in 8.272 seconds; six inspection tests passed in 3.868 seconds; four acquisition boundary tests and nine bundle scenarios passed, including relocated BTA compilation, JUnit execution and offline startup without compiler artifacts. CLI tests cover online annotations resolution, schema validity, effective identity, offline read-only behavior, corrupt blob preservation/explicit repair, policy staleness, unsupported fields, symlinks, aliases and concurrent publishers.

Independent review reproduced two original publication defects: a case alias overwrote the project, and distinct project roots raced on one destination using different mutexes. Both were fixed. The reviewer reran the alias probe and three shared-destination barrier races against the final composition: original project bytes remained intact; each race published one inode, with one successful process and one exit-3 rejection during resolution before overwrite. An eighth permanent CLI test now holds the shared mutex until both projects have captured their publication preconditions, then verifies the winning lock and pre-publication rejection; its focused run passed in 0.263 seconds. The separate graph/provider slices do not yet establish successful consumer CLI builds, upstream Native test coverage, or remote compiler reuse.
