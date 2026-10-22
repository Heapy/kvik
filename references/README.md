# Upstream source references

These submodules are source references for Kvik's design and compatibility work.
They are not part of the bootstrap build or consumer execution.

| Path | Upstream | Relevant area |
| --- | --- | --- |
| `kotlin/` | https://github.com/JetBrains/kotlin | Kotlin Gradle plugin in `libraries/tools/kotlin-gradle-plugin/`, compiler integration and KMP metadata |
| `gradle/` | https://github.com/gradle/gradle | Dependency resolution, execution and caching |
| `kotlin-toolchain/` | https://github.com/JetBrains/kotlin-toolchain | Declarative project model, dependency resolution and compiler orchestration |

The parent repository's gitlinks pin exact upstream commits. Initial snapshots
use upstream default branches with shallow history; they do not establish the
compiler or bootstrap version pins proposed in `../PLAN.md`.

After cloning Kvik, fetch the pinned sources with:

```sh
git submodule update --init --depth 1
git submodule status
```

Submodule initialization is optional for ordinary Kvik work. Nested upstream
submodules are not initialized automatically. Update these snapshots explicitly
and review the resulting gitlink changes; avoid automatic remote updates in CI.

Review upstream licenses and retain provenance before copying implementation code.
