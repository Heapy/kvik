package kvik.cli

import kvik.core.*
import kvik.projectgraph.supportsCoreChangeSelection

/** A path-impact query, never a verification result or permission to omit checks. */
internal fun inspectFileResult(model: ProjectInspection, configurationPath: LogicalPath, file: LogicalPath,
                               checkpoint: () -> Unit = {}): JsonValue.Object {
    fun matches(root: LogicalPath) = file == root || file.value.startsWith(root.value + "/")
    val owners = mutableListOf<JsonValue>()
    val settings = linkedMapOf<String, JsonValue>()
    val generators = mutableListOf<JsonValue>()
    val checks = mutableListOf<JsonValue>()
    val fullReasons = linkedSetOf<String>()
    if (file == configurationPath) fullReasons += "configuration-path"
    if (!supportsCoreChangeSelection(buildPlugins)) fullReasons += "source-build-plugins"
    val modules = model.project.document.objectValue().getValue("modules").arrayValue()
    for ((moduleIndex, module) in model.project.project.modules.withIndex()) {
        checkpoint()
        val moduleDocument = modules[moduleIndex].objectValue()
        for (sourceSet in module.sourceSets) {
            val scriptInputs = scriptGenerators(model.project.project, module.id, setOf(sourceSet.name)).filter { script ->
                (script.declaration.sources + script.declaration.resources + script.declaration.generator!!.inputs +
                    script.compilerPlugins.flatMap { it.value.inputs }).any(::matches)
            }
            val roles = buildList {
                if (sourceSet.directories.any(::matches)) add("source")
                if (sourceSet.resources.any(::matches)) add("resource")
                if (scriptInputs.isNotEmpty() || module.sqlDelight?.let { it.sourceSet == sourceSet.name && matches(it.sourceDirectory) } == true) add("generator-input")
            }
            if (roles.isEmpty()) continue
            val targets = module.targets.filter { sourceSet in sourceSetClosure(module, listOfNotNull(it.main, it.test)) }
            owners += obj("module" to module.id.json(), "sourceSet" to sourceSet.name.json(), "roles" to roles.map { it.json() }.json(),
                "targets" to targets.map { it.name.json() }.json(),
                "origin" to model.origins.getValue("sourceSet:${module.id}:${sourceSet.name}").toJson())
            if (module.sqlDelight?.let { it.sourceSet == sourceSet.name && matches(it.sourceDirectory) } == true) generators += obj("module" to module.id.json(), "kind" to "sqldelight".json(),
                "sourceSet" to sourceSet.name.json(), "origin" to model.origins.getValue("generator:${module.id}:sqldelight").toJson())
            for (script in scriptInputs) generators += obj("module" to module.id.json(), "kind" to "script".json(), "script" to script.name.json(),
                "sourceSet" to sourceSet.name.json(), "origin" to ConfigurationOrigin(configurationPath, "/scripts/${script.name}/generator").toJson())
        }
        for ((targetIndex, target) in module.targets.withIndex()) {
            checkpoint()
            val kinds = buildSet { add("build"); if (target.test != null) add("test"); if (target.abi != null) add("abi-check") }
            val candidates = kinds.map { "${module.id}:${target.name}:$it" }.toSet()
            // Supplying the candidate set suppresses the selector's baseline-missing rule.
            // Only path/configuration impact is returned; no baseline is asserted by this query.
            val impact = selectAffectedChecks(model.project, setOf(module.id), setOf(target.name), kinds, setOf(file), candidates, fullReasons)
            for (id in impact.selected.sorted()) checks += obj("id" to id.json(), "module" to module.id.json(), "target" to target.name.json(),
                "kind" to id.substringAfterLast(':').json(), "reasons" to impact.reasons.getValue(id).map { it.json() }.json())
            fullReasons += impact.fullReasons
            if (impact.selected.isNotEmpty()) settings["${module.id}:${target.name}"] = obj(
                "declaration" to moduleDocument.getValue("targets").arrayValue()[targetIndex],
                "mainCompilerOptions" to target.optionsFor("main").map { it.json() }.json(),
                "testCompilerOptions" to (if (target.test == null) JsonValue.Null else target.optionsFor("test").map { it.json() }.json()),
                "origin" to model.origins.getValue("target:${module.id}:${target.name}").toJson())
        }
    }
    val scripts = model.project.project.scripts.entries.sortedBy { it.key }.mapNotNull { (name, declaration) ->
        val expanded = expandScript(model.project.project, name)
        val roles = buildList {
            if (declaration.sources.any(::matches)) add("source")
            if (declaration.resources.any(::matches)) add("resource")
            if (expanded.compilerPlugins.any { plugin -> plugin.value.inputs.any(::matches) }) add("compiler-plugin-input")
            if (declaration.generator?.inputs?.any(::matches) == true) add("generator-input")
        }
        if (roles.isEmpty()) null else obj("name" to name.json(), "roles" to roles.map { it.json() }.json(),
            "origin" to ConfigurationOrigin(configurationPath, "/scripts/$name").toJson())
    }
    return obj("schemaVersion" to CONTRACT_VERSION.json(), "kind" to "file-inspection".json(), "file" to file.value.json(),
        "observed" to model.files.any { it.path == file }.json(), "configurationPath" to configurationPath.value.json(),
        "configuration" to model.configuration.toJson(), "composition" to model.composition.toJson(),
        "configurationScope" to "declaration-and-resolver-policy".json(),
        "tags" to model.files.singleOrNull { it.path == file }?.tags.orEmpty().map { it.json() }.json(),
        "owners" to owners.json(), "scripts" to scripts.json(), "generators" to generators.json(),
        "checks" to checks.json(), "settings" to JsonValue.Object(settings), "fullReasons" to fullReasons.sorted().map { it.json() }.json(),
        "limitations" to listOf(
            "Ownership follows declared paths, including proposed or deleted files; observed only describes a regular file seen during inspection.",
            "Checks describe conservative build/test/ABI impact, including downstream modules and workspace compiler plugins. They do not establish passing results or authorize omissions.",
            "Direct script ownership is listed separately; script execution, documentation and publication are outside the check selection.",
            "Script annotations and imports are not expanded; configuration identifies the project declaration and resolver policy only.",
            "Configuration inspection does not validate installed tools, dependency locks or target execution availability."
        ).map { it.json() }.json()).let { result ->
            val value = if (model.source == null) result else JsonValue.Object(result.values + ("source" to model.source.toJson()))
            require(value.canonical().encodeToByteArray().size <= 16 * 1024 * 1024) { "File inspection exceeds 16 MiB" }
            value
        }
}
