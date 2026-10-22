# Native project input capture

`kvik.projectsnapshot.NativeProjectSnapshotter` captures only declared source/resource directories and explicit compiler-plugin input files from a validated project configuration. It reads the exact configuration and dependency-lock bytes, including authoring whitespace. The lock may be an absolute path outside the project. No whole-project or Git scan occurs, and generated `.kvik` paths cannot be declared or recursively visited.

```kotlin
val capture = NativeProjectSnapshotter(projectRoot, contentStore).capture(
    LogicalPath("kvik.json"), absoluteLockPath,
)
when (capture) {
    is SnapshotCapture.Captured -> {
        val snapshot = capture.snapshot
        // snapshot.configuration, configurationBytes, lockBytes
        // snapshot.inputs: project-relative TreeManifest for projectActionGraph
        // snapshot.fingerprint: complete capture identity for verification
    }
    is SnapshotCapture.Indeterminate -> error(capture.reason)
}
```

`ProjectInputSnapshot.inputs` contains source/resource/plugin files with content digests and executable bits. Configuration and lock bytes are separate fields and enter the complete fingerprint; they enter the graph manifest only if a declared input explicitly contains them. Returned byte arrays are defensive copies. Capture publishes all observed file bytes, including configuration and lock, to the supplied `ContentStore` and checks its returned digests. A failed/indeterminate capture may leave unreferenced immutable CAS blobs.

The fingerprint hashes canonical `kvik-project-input-snapshot-v1` JSON: exact configuration/lock byte digests, configuration logical path, declared roots and plugin paths, the input tree, and inventories for all visited directories. A missing declared root and an existing empty directory have distinct identities. Each existing directory records sorted child names and file/directory kinds, including empty subdirectories. Added, deleted, renamed, untracked, or same-size/mtime-preserving changed inputs affect identity. Absolute checkout and lock locations are not portable input identity; the snapshot retains their locations separately for freshness. The configuration's full declaration set is captured, including unselected modules/targets; selection belongs to the graph/coordinator.

`freshness(snapshot)` returns core `Freshness.CURRENT`, `STALE`, or `INDETERMINATE`. For a later invocation without the captured object, `currentFingerprint(configurationPath: LogicalPath, lockPath: String): Digest?` computes the same identity. It returns null on inconsistent observations and can throw on unsafe initial inputs. Both queries perform two complete scans without any CAS reads, writes, repairs, directory creation, or network access. The coordinator can persist root/configuration path/absolute lock path/fingerprint and compare a later digest; it must map null/errors to indeterminate verification. A snapshot belonging to a different root is rejected by `freshness`.

Capture compares two complete observations before returning `Captured`. Each read checks file identity, mode, length, mtime and ctime before/after; directory enumeration similarly checks its directory state. Directory handles and files reject final symlinks, every declared ancestor is checked, and duplicate device/inode identities under distinct paths reject hard-link and filesystem case aliases. Source/resource roots may be missing, but configuration, lock and explicit plugin files are required. Links, special files, unsafe logical names, generated `.kvik` entries, alias collisions, and configured bounds fail closed. Ordinary initial invalid input raises `SnapshotFailure`; an observed race or inconsistent second scan yields `Indeterminate`. Freshness turns invalid or unstable observations into `INDETERMINATE`.

This is **not an atomic filesystem snapshot**. Project, lock, and traversed directory ancestors must be trusted and must not be renamed or replaced adversarially during capture/use. Two scans cannot exclude ABA changes, malicious timestamp restoration, or an edit after the final observation. Directory metadata checks are race detection, not an inode-pinned sandbox. Before/after capture identity gives conservative evidence for the observed intervals; the coordinator must check freshness after execution before claiming current results. The capture does not verify dependency-lock schema/CAS metadata proofs or compiler-tool provenance; those remain their existing adapters' responsibility.

Default per-observation bounds: 20,000 unique files including configuration/lock, 10,000 declared/visited directories including missing roots, 40,000 enumerated entries and at most 40,000 unique declared paths, 64 directory/path components, 64 MiB per file, 256 MiB aggregate bytes. Configuration is additionally limited to 4 MiB and lock to 16 MiB. `SnapshotLimits` permits bounded overrides. Bytes are read into memory; the implementation is synchronous and is not intended to run on a responsive scheduler event loop. No consumer processes are launched.
