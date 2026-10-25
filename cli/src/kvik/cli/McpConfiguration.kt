package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.mcp.McpTool

/** Stage inline documents in request-owned scratch; the CLI remains the edit validator/publisher. */
internal fun mcpConfigurationArguments(tool: McpTool, arguments: List<String>, options: McpOptions, directory: String): List<String> {
    val planning = tool.name == "kvik_config_plan"
    if (!planning && tool.name != "kvik_config_apply") return arguments
    require(options.configurationEdits) { "Configuration edits require mcp --config-edits" }
    val inline = if (planning) "--edits-json" else "--plan-json"
    val index = arguments.indexOf(inline)
    require(index >= 0 && index + 1 < arguments.size) { "Missing configuration document" }
    val bytes = arguments[index + 1].encodeToByteArray()
    if (!planning) {
        val plan = parseJson(arguments[index + 1]).objectValue()
        require(plan["kind"] == "configuration-edit-plan".json() && plan["schemaVersion"] == CONTRACT_VERSION.json()) {
            "Expected a local configuration-edit-plan"
        }
        require(plan.getValue("root").stringValue() == options.root &&
            plan.getValue("project").stringValue() == options.project.substringAfterLast('/')) {
            "Configuration plan belongs to another project"
        }
        val configured = projectOptions(options.policy + listOf("--project", options.project) +
            options.lock?.let { listOf("--lock", it) }.orEmpty() + options.store?.let { listOf("--store", it) }.orEmpty(), lockOptions = true)
        require(projectPath(options.root, plan.getValue("lock").stringValue()) == projectPath(options.root, configured.lock) &&
            projectPath(options.root, plan.getValue("store").stringValue()) == projectPath(options.root, configured.store) &&
            plan.getValue("policy") == policyDocument(configured)) {
            "Configuration plan uses a different lock, store or resolver policy"
        }
    }
    val file = "$directory/configuration-input.json"
    NativeFiles.writeNew(file, bytes)
    return arguments.toMutableList().also {
        it[index] = if (planning) "--edits" else "--plan"
        it[index + 1] = file
    }
}
