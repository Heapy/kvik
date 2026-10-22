# Implementation status

PLAN.md remains the acceptance specification. A working spike does not mark the prototype complete.

| Item | Status | Evidence / remaining work |
| --- | --- | --- |
| K01 | Feasibility complete | [Bootstrap decision](decisions/0001-bootstrap.md), [results](evidence/k01.md); production bootstrap/lifecycle gaps explicitly retained |
| K02 | Initial contracts complete | [Contracts](decisions/0002-contracts.md), 18 passing Kotlin/Native tests including project validation, 11 schema examples; semantic validation remains mandatory at adapter boundaries |
| K03 | Execution slice implemented | [Scheduler and ownership evidence](evidence/k03.md); independent review and event-schema correction; CLI run coordination remains K09 |
| K04 | Local store implemented | [Native cache evidence](evidence/k04.md), 17 passing integration checks; independent review, explicit macOS/size/trusted-root limits |
| K05 | Production dependency slice implemented | [Resolution evidence](evidence/k05.md); 12 binary artifacts match the reference; [offline lock replay](evidence/k09-resolution.md) and [Native CLI publication/checks](evidence/k09-dependency-cli.md); broader test/plugin metadata remain |
| K06 | Workers and Native action bridge implemented | [JVM evidence](evidence/k06.md); all 2,038 reference tests pass; [Native bridge](evidence/k06-native.md) executes scheduled BTA/JUnit actions with reviewed result/classpath fixes; production providers, CLI and compilation-cache integration remain |
| K08 | Adapter slice implemented | [Native evidence](evidence/k08.md); unchanged production modules compile; reviewed cancellation/parser fixes; upstream Native Kotest/KSP tests and full CLI integration remain open |
| K07 | Client slice implemented | [Remote cache evidence](evidence/k07.md); 13 backend scenario groups and independent wire review; synchronous bounded I/O, no compiler portability approval |
| K09 | In progress | [Read-only native inspection](evidence/k09.md), reviewed [dependency commands](evidence/k09-dependency-cli.md) and [full Native tool verification](evidence/k09-toolchains.md); build/test/run evidence integration remains |
| K10–K11 | Pending | Adversarial validation and decision |
| K12–K13 | Pending | Scripts and workspace compiler plugins |
| K14 | In progress | [Local source composition and launcher](evidence/k14.md), verified native helper and source-built JVM worker bundle, 17 launcher checks; remote source provisioning/shared executable cache integration remain |
| K15 | Pending | Foreground continuous session |

Automatic fixtures, test avoidance, an optional multiplatform test framework and `@Task` effect checking remain research directions. None is implemented or promised by the K01 spike.
