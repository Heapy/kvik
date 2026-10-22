# K09 declarative JVM test selections

`TargetSpec` has a final optional `testSettings: JvmTestSettings? = null` constructor parameter. Existing four-argument Kotlin constructors and JSON documents remain valid. Settings are allowed only on a JVM target that declares a test source set.

```json
"testSettings": {
  "classes": ["example.ParserTest", "example.Container$Nested"],
  "engines": ["junit", "kotest"]
}
```

`classes` is required, ordered and unique. Supported binary names have ASCII letter/underscore/dollar identifier starts and letter/digit/underscore/dollar continuations, separated by single dots. Default-package and dollar-containing nested binary names are supported. Wildcards, method selectors, whitespace, empty name segments and non-ASCII identifiers are outside this bounded contract. `engines` is optional and defaults to `["junit"]`; explicit engine lists must be nonempty, unique and contain only `junit` and/or `kotest`. The CLI maps `junit` to the existing worker engine ID `junit-jupiter`.

Omitted settings remain `null`; build configuration does not require a test selection. The CLI must report an actionable missing-selection error when testing a JVM target without settings. Explicit `classes: []` requests no classes and must retain the existing incomplete/zero-test behavior. Neither form authorizes implicit classpath scanning. CLI and graph wiring are separate integration work.

The parser retains the original JSON document unchanged. Class order, engine order, changed selections and omitted-versus-explicit defaults therefore retain their normal document identity behavior. Inspection already exposes this retained document; no CLI changes are needed to preserve these fields.

Validation:

```sh
python3 tools/generate_schemas.py
python3 tools/test_contracts.py \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python tests/contracts/test_project_settings_schema.py
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python tools/check_schemas.py
```

The schema checks use the existing environment provisioned from `tools/schema-requirements.txt`. Three focused Native tests cover defaults/order/identity, backwards compatibility and invalid settings/target scopes; two schema parity tests cover the same valid and invalid configuration shapes. Existing contract and schema examples are also checked. These are parser/model/schema checks, not claims of consumer test execution or CLI missing-selection behavior.

Observed: all 21 Kotlin/Native contract tests, both schema parity tests and all 11 existing schema examples passed.
