# Kvik

An experimental Kotlin Multiplatform build tool, distributed with its build plugins as sources and compiled into a cached **Kotlin/Native** executable. Invocations own their workers; repeated development work will use an explicit foreground session.

The implementation is at the bootstrap/contracts stage. There is no production consumer-build CLI yet. See [PLAN.md](PLAN.md), [current status](docs/STATUS.md), [bootstrap evidence](docs/evidence/k01.md) and [v1 contracts](docs/decisions/0002-contracts.md).

## Current checks

On macOS ARM64, supply a Kotlin/Native 2.4.20 installation and Liberica JDK 25.0.4:

```sh
python3 experiments/k01/bootstrap.py --java-home "$JDK" --konan-home "$KONAN"
# Set BUILD to the directory printed above.
python3 experiments/k01/verify.py --build-dir "$BUILD" --java-home "$JDK" --konan-home "$KONAN"
python3 tools/test_contracts.py --java-home "$JDK" --konan-home "$KONAN"
```

The bounded spike downloads checksum-locked compiler artifacts and compiles sources directly. It does not call Gradle or Kotlin Toolchain to compile consumer code. The reference baseline separately uses the fixture's existing KTC build for comparison.

Schema validation uses development-only Python dependencies:

```sh
python3 -m venv .kvik/schema-tools
.kvik/schema-tools/bin/pip install -r tools/schema-requirements.txt
.kvik/schema-tools/bin/python tools/check_schemas.py
```

Upstream source submodules under `references/` are optional design references, not runtime/bootstrap dependencies. See [their provenance](references/README.md).
