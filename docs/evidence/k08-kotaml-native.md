# K08 unchanged upstream Native Kotest parity

The owned Native adapters rebuilt and executed the pinned kotaml common-source
fixture at commit `a5c94c77b220cfc7b6335668766e8f26930968c3`. All 1,880 expected
leaf identities passed, each executed twice: 1,558 in kotaml and 322 in
kotaml-json, with zero failures or skips. Each module's second main compilation,
KSP generation, and Native link was a verified local cache hit; both test
invocations executed and emitted complete independent typed journals.

The audit copies 61 unchanged files from `src`, `test`, `kotaml-json/src`, and
`kotaml-json/test`, requiring an exact inventory match against the previously
pinned source manifest. It never edits the original fixture. Native main
libraries, official KSP launchers, and test executables are built in private
owned directories; no reference-build binaries are reused. The root tests
recompile their main sources for the established Native friend-access behavior.
The JSON module consumes the freshly built root library. Each file in every
successful output manifest is independently rehashed from CAS.

`tests/native/kotaml-test-identities.json` pins the exact sorted
`{module,target,id}` list. Extraction independently compared the complete unique
leaf IDs from both retained K01 Native cold and warm logs, whose digests are
recorded in the file. No IDs are normalized and no expected list is inferred
from aggregate counts. The manifest's SHA-256 is
`6281a9f9bfa16eee58bcf0693bada1936179af09762bfd6223a0de263199b423`.
The retained logs supply baseline identity evidence; this audit does not rerun
or claim independent reconstruction of the reference build itself.

`tests/native/kotaml-artifacts.json` pins ordered external Native klib and
serialization-plugin bytes extracted from retained KTC compile commands.
Own-project klibs are excluded from those pins and rebuilt. KSP runtime and
processor jars use the separate pinned lifecycle fixture classpaths. These
are explicit adapter-fixture inputs, not substitutes for a production
resolver lock. Fresh six-component installed-tool inventories are checked
once before the fixture sequence. The fixture provider thereafter checks
requested digests against that verified layout; stable caller-controlled
roots are required. Production providers continue fresh verification per call.

## Reproduction

First build the Native fixture probe using `tools/test_kotest_native.py --stage
bootstrap` with the installed tool arguments from `k08-kotest-adapters.md`.
Then:

```sh
python3 tools/test_kotaml_native.py \
  --fixture /Users/yoda/dev/pet/kvik/.kvik/fixtures/kotaml \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --konan-data /Users/yoda/.konan \
  --developer-directory /Applications/Xcode.app/Contents/Developer \
  --sdk /Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk \
  --ktc-cache /Users/yoda/Library/Caches/JetBrains/Kotlin/.m2.cache
```

The audit refuses stale probe source or binary digests. All consumer KSP,
compiler, and test subprocesses use the Native K03 owner. The initial fixture
probe bootstrap remains an explicitly invoked Python/toolchain step.

## Retained result

Run directory: `.kvik/tests/kotest-native/kotaml-xl6z4lit`.
`summary.json` SHA-256:
`e6b0ee7d08859f4f7c7cbc202fa654e4ed740120fafa0b3c7b11833bb443c704`.
It retains complete action recipes, exact tool/input/output identities,
copied source subset, raw official launchers, logs, and typed journals.

| Module | Main compile | KSP generation | Native link | Test first / repeat |
| --- | ---: | ---: | ---: | ---: |
| kotaml | 3.614 s | 3.095 s | 58.623 s | 1.069 / 0.940 s |
| kotaml-json | 1.799 s | 2.820 s | 44.422 s | 0.718 / 0.530 s |

Root journals are 1,182,542 bytes each, with SHA-256
`3ebe3d27cb83b8d8e63260acbba780bc6b6ab0970514a76c4370da82796dbfa9`
and `5e95e2102f7011b00d5c6cdaa0293493e856a1a3b7616edf2ec79675f994eb77`.
JSON journals are 200,400 bytes each, with SHA-256
`b08fddc840b5df277ec0bf3028aeb113d560660e7cdab6ed5f3c1d1a7d26c9f1`
and `2467187ee6d484636f92a259ada50f9f4bc2e57a18ca81d332762ef92c499674`.
Per-invocation tokens differ; leaf identities match exactly.

This proves direct owned-adapter execution and local-cache behavior for this
unchanged pinned fixture. Pure graph checks are separate, and public CLI
composition/dispatch remains a separate integration gate. No remote eligibility,
cross-machine portability, application restart, or general Native target support
is claimed. The ByteArray store/materializer retains its documented memory limits.
