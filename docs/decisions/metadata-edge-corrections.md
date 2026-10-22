# Explicit corrections to published dependency edges

A consumer may explicitly exclude one ordinary Gradle metadata dependency edge
when the producer metadata is known to be incorrect. Use repeatable
`--exclude-metadata-edge JSON` on project commands. Each JSON value has these
required fields:

- `domain`: module, target, compilation, usage and purpose, matching a selected
  dependency resolution domain.
- `parent`: exact producer coordinate, including its version.
- `metadata`: SHA-256 digest and byte size of the producer's module metadata.
- `variant`: exact selected producer variant name.
- `dependency`: exact declared dependency coordinate before applying version pins.
- `reason`: an explicit explanation of the correction.

The JSON argument is bounded to 4 KiB of UTF-8, with at most 32 rules. Duplicate
or conflicting corrections are invalid. Rules cannot exclude direct roots,
redirects, platform/BOM constraints or POM dependencies. A changed parent version,
metadata digest, variant or declared edge makes the policy stale. A rule must
apply somewhere in its full consumer domain; per-root internal replay may leave
rules for other roots unapplied.

Rules enter the effective configuration identity and dependency lock. Whitespace,
object field order and argument order do not change that identity. Changing a
reason does change it. Offline checks verify the same locked metadata and replay
the same correction. Supply the policy consistently to resolution, inspection,
build/test, foreground sessions and project dependency queries. Build reproduction
arguments and resumed foreground sessions retain canonical JSON arguments.

The `dependencies --json` graph retains an explicit excluded edge with its
original declared requirement, rule identity and reason, without a selected target.
Exclusion does not claim that a platform supplies the dependency or that arbitrary
code using it is supported. Raw `packages dependencies` has no consumer domain;
it rejects this project-scoped flag and continues to describe published metadata.

The initial use case is the pinned Kotest 6.2.5 Native metadata edge to the JVM
`kotlin-reflect` artifact. The reference Kotlin Toolchain drops JVM jars from
Native compiler inputs. Kvik requires the correction to be explicit and bound to
that metadata instead of silently filtering incompatible artifact kinds. The
unchanged Native source/test gate must still pass with the corrected dependency
set; the rule itself is not proof of compatibility.
