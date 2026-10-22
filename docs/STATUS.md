# Implementation status

PLAN.md remains the acceptance specification. A working spike does not mark the prototype complete.

| Item | Status | Evidence / remaining work |
| --- | --- | --- |
| K01 | Feasibility complete | [Bootstrap decision](decisions/0001-bootstrap.md), [results](evidence/k01.md); production bootstrap/lifecycle gaps explicitly retained |
| K02 | Initial contracts complete | [Contracts](decisions/0002-contracts.md), 18 passing Kotlin/Native tests including project validation, 11 schema examples; semantic validation remains mandatory at adapter boundaries |
| K03 | Execution slice implemented | [Scheduler and ownership evidence](evidence/k03.md); independent review and event-schema correction; CLI run coordination remains K09 |
| K04 | Local store implemented | [Native cache evidence](evidence/k04.md), 17 passing integration checks; independent review, explicit macOS/size/trusted-root limits |
| K05 | Production dependency slice implemented | [Resolution evidence](evidence/k05.md); 12 binary artifacts match the reference, bounded metadata subset; test/plugin closures and persistent project lock integration remain |
| K06 / K08 | In progress | JVM and Native compiler/test adapters in isolated worktrees |
| K07 | In progress | Native remote-cache adapter in isolated worktree |
| K09 | In progress | [Read-only native inspection](evidence/k09.md); build/test/run evidence integration remains |
| K10–K11 | Pending | Adversarial validation and decision |
| K12–K13 | Pending | Scripts and workspace compiler plugins |
| K14 | In progress | [Local source composition and launcher](evidence/k14.md), Kotlin/Native executable reuse, ownership and invalidation checks; remote source provisioning/shared cache/helper integration remain |
| K15 | Pending | Foreground continuous session |

Automatic fixtures, test avoidance, an optional multiplatform test framework and `@Task` effect checking remain research directions. None is implemented or promised by the K01 spike.
