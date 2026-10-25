package kvik.core

/** Shared declaration view for configuration-only and locked graph inspection. Order is significant. */
fun ExpandedScript.inspectionFields(): Map<String, JsonValue> = mapOf(
    "declaration" to declaration.toJson(),
    "dependencies" to dependencies.map { value ->
        val reference = when (val dependency = value.value) {
            is Dependency.External -> obj("coordinate" to dependency.coordinate.toString().json())
            is Dependency.Module -> obj("module" to dependency.module.json())
        }
        obj("reference" to reference, "scope" to value.value.scope.name.lowercase().json(), "origin" to value.origin.toJson())
    }.json(),
    "compilerOptions" to compilerOptions.map { obj("value" to it.value.json(), "origin" to it.origin.toJson()) }.json(),
    "compilerPlugins" to compilerPlugins.map { obj("reference" to (it.value.coordinate?.toString()?.json() ?: it.value.module!!.json()),
        "kind" to (if (it.value.module == null) "published" else "workspace").json(), "origin" to it.origin.toJson(),
        "id" to (it.value.id?.json() ?: JsonValue.Null), "compilerVersion" to (it.value.compilerVersion?.json() ?: JsonValue.Null),
        "options" to it.value.options.map(String::json).json(), "inputs" to it.value.inputs.map { path -> path.value.json() }.json(),
        "pathOptions" to it.value.pathOptions.map { option -> option.toJson() }.json(), "outputs" to it.value.outputs.map { path -> path.value.json() }.json()) }.json(),
)
