# Implementation status

PLAN.md remains the acceptance specification. A working spike does not mark the prototype complete.

| Item | Status | Evidence / remaining work |
| --- | --- | --- |
| K01 | Feasibility complete | [Bootstrap decision](decisions/0001-bootstrap.md), [results](evidence/k01.md); production bootstrap/lifecycle gaps explicitly retained |
| K02 | Initial contracts complete | [Contracts](decisions/0002-contracts.md), 14 passing Kotlin/Native tests, 11 schema examples; semantic validation remains mandatory at adapter boundaries |
| K03–K11 | Pending | Implementation, integration, adversarial validation and decision |
| K12–K13 | Pending | Scripts and workspace compiler plugins |
| K14–K15 | Pending | Production native source launcher and foreground continuous session |

Automatic fixtures, test avoidance, an optional multiplatform test framework and `@Task` effect checking remain research directions. None is implemented or promised by the K01 spike.
