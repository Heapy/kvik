# Implementation status

PLAN.md remains the acceptance specification. A working spike does not mark the prototype complete.

| Item | Status | Evidence / remaining work |
| --- | --- | --- |
| K01 | Feasibility complete | [Bootstrap decision](decisions/0001-bootstrap.md), [results](evidence/k01.md); production bootstrap/lifecycle gaps explicitly retained |
| K02 | Initial contracts complete | [Contracts](decisions/0002-contracts.md), 18 passing Kotlin/Native tests including project validation, 11 schema examples; semantic validation remains mandatory at adapter boundaries |
| K03 | Execution slice implemented | [Scheduler and ownership evidence](evidence/k03.md); independent review and event-schema correction; [one-shot CLI coordination](evidence/k09-build-cli.md) integrated; foreground sessions remain K15 |
| K04 | Local store implemented | [Native cache evidence](evidence/k04.md), 17 passing integration checks; independent review, explicit macOS/size/trusted-root limits |
| K05 | Pinned kotaml dependency slice implemented | [Resolution evidence](evidence/k05.md), [offline lock replay](evidence/k09-resolution.md), [Native CLI publication/checks](evidence/k09-dependency-cli.md) and [per-compilation domains](evidence/k09-dependency-scopes.md); reviewed [capabilities](evidence/k05-capabilities.md) and [parent POMs](evidence/k05-parent-poms.md) reproduce all eight kotaml dependency sets and offline replay; broader Maven/Gradle semantics remain explicitly limited |
| K06 | Full kotaml JVM CLI fixture validated | [Worker/adapter evidence](evidence/k06.md), [Native bridge](evidence/k06-native.md), [small CLI fixture and local reuse](evidence/k09-build-cli.md); [full public CLI run](evidence/k09-kotaml-cli.md) passes all 2,038 exact test identities and all eight external artifact sets from unchanged sources; classpath ordering and JDK API-surface equivalence are not claimed |
| K08 | Adapter and small CLI fixture implemented | [Native evidence](evidence/k08.md); unchanged production modules compile; [two-module CLI fixture](evidence/k09-build-cli.md) proves common/actual, tests, local reuse, failure and zero-test handling; upstream Native Kotest/KSP tests remain open |
| K07 | Client slice implemented | [Remote cache evidence](evidence/k07.md); 13 backend scenario groups and independent wire review; synchronous bounded I/O, no compiler portability approval |
| K09 | In progress | [Read-only native inspection](evidence/k09.md), reviewed [dependency commands](evidence/k09-dependency-cli.md), [full Native tool verification](evidence/k09-toolchains.md), [validated JVM/Native CLI and retained evidence](evidence/k09-build-cli.md), [full kotaml JVM run](evidence/k09-kotaml-cli.md); action explanations and broader interface gates remain |
| K10–K11 | Pending | Adversarial validation and decision |
| K12–K13 | Pending | Scripts and workspace compiler plugins |
| K14 | In progress | [Local source composition and launcher](evidence/k14.md), verified native helper and source-built JVM worker bundle, 17 launcher checks; remote source provisioning/shared executable cache integration remain |
| K15 | In progress | Initial library build/test foreground session in a separate worktree; lifecycle validation, source-plugin recomposition and application restart remain pending |

Automatic fixtures, test avoidance, an optional multiplatform test framework and `@Task` effect checking remain research directions. None is implemented or promised by the K01 spike.
