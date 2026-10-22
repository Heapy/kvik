# K09 Native project capture and freshness evidence

Implementation: `integration/project-snapshot`. Only that directory, its dedicated tests/harness and this evidence file change. Core, cache, CLI and bootstrap remain unchanged.

Reproduce:

```sh
python3 tools/test_project_snapshot.py \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca
```

The harness builds a Kotlin/Native macos_arm64 test runner with the compiler's offline dependency property. Nine test groups use real temporary filesystem trees and a counting in-memory ContentStore:

Observed result on the provisioned host: all 9 groups passed (71 ms test execution); compilation completed without warnings. `git diff --check` passed.

Independent review identified that files under an allowed-depth directory could exceed the component bound. A follow-up applies the same bound to discovered files. The regression uses only flat roots: depth 1 rejects their two-component file paths, depth 2 accepts them. All 9 groups passed again (57 ms).

- Declared source/resource/plugin bytes, exact configuration bytes and an external lock are retained and stored with verified digests. Unrelated project and generated `.kvik` changes leave identity current.
- Freshness and post-exit `currentFingerprint` perform no CAS operations, proven with a store whose get/put both throw. Returned byte arrays cannot mutate the stored capture.
- Same-size content edits with restored timestamps invalidate; executable-mode changes invalidate.
- Creation of missing declared roots and empty subdirectories, file additions/deletions/renames, and empty directory removal invalidate.
- Configuration whitespace, lock bytes and plugin input bytes affect identity. Invalid or deleted required authoring inputs produce indeterminate freshness.
- Symlinks, FIFOs, hard-link aliases, case aliases on the provisioned filesystem, symlink ancestors, missing required plugin inputs, and `.kvik` declarations/descendants reject.
- ContentStore publication hooks deterministically edit an already-read source, an already-enumerated resource directory, or the already-read configuration. Capture returns indeterminate rather than publishing a mixed observation as current.
- File/directory/entry/depth/per-file/aggregate byte bounds fail closed.

The ContentStore test double only provides immutable byte storage and mutation injection; directory scanning, hashing, POSIX reads, alias detection and all mutation observations run natively against real files. The suite does not claim an atomic snapshot, hostile ancestor-race protection, asynchronous responsiveness, actual consumer compilation, or integrated CLI verification publication. Trusted stable ancestors and unavoidable observation/ABA limits are documented in `integration/project-snapshot/README.md`.
