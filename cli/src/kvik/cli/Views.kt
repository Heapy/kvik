@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.cache.NativeContentStore
import kvik.core.*
import kvik.execution.platform.*

/** A view exports a projection of a pinned inventory; it never changes the source revision. */
internal fun viewCommand(args: List<String>): JsonValue {
    val (source, remaining) = sourceProjectOptions(args)
    require(source != null) { "A source view requires --kode, --kode-home, --source-project and --revision" }
    var role: String? = null
    var policyName: String? = null
    val all = mutableSetOf<String>()
    val none = mutableSetOf<String>()
    val editAll = mutableSetOf<String>()
    val editAny = mutableSetOf<String>()
    val editNone = mutableSetOf<String>()
    val capabilities = mutableSetOf<String>()
    var allowAllEdits = false
    val projectArgs = mutableListOf<String>()
    var index = 0
    while (index < remaining.size) {
        val flag = remaining[index++]
        if (flag == "--allow-all-edits") {
            require(!allowAllEdits) { "Duplicate --allow-all-edits" }
            allowAllEdits = true
            continue
        }
        if (flag !in setOf("--role", "--tag", "--exclude-tag", "--policy-name", "--edit-tag", "--edit-any-tag", "--exclude-edit-tag", "--allow")) {
            projectArgs += flag
            continue
        }
        val value = remaining.getOrNull(index++)
        require(value != null && value.isNotBlank() && !value.startsWith("--")) { "Missing value for $flag" }
        when (flag) {
            "--role" -> { require(role == null) { "Duplicate --role" }; role = value }
            "--tag" -> require(all.add(value)) { "Duplicate --tag: $value" }
            "--exclude-tag" -> require(none.add(value)) { "Duplicate --exclude-tag: $value" }
            "--policy-name" -> { require(policyName == null) { "Duplicate --policy-name" }; policyName = value }
            "--edit-tag" -> require(editAll.add(value)) { "Duplicate --edit-tag: $value" }
            "--edit-any-tag" -> require(editAny.add(value)) { "Duplicate --edit-any-tag: $value" }
            "--exclude-edit-tag" -> require(editNone.add(value)) { "Duplicate --exclude-edit-tag: $value" }
            "--allow" -> require(capabilities.add(value)) { "Duplicate --allow: $value" }
        }
    }
    val name = requireNotNull(role) { "Select --role production, tests or all" }
    val predicate = roleFilePredicate(name, all, none)
    val hasEditSelection = allowAllEdits || editAll.isNotEmpty() || editAny.isNotEmpty()
    require(policyName != null || (!hasEditSelection && editNone.isEmpty() && capabilities.isEmpty())) {
        "Edit rules and capabilities require --policy-name"
    }
    require(editNone.isEmpty() || hasEditSelection) { "--exclude-edit-tag requires --edit-tag, --edit-any-tag or --allow-all-edits" }
    require(!allowAllEdits || (editAll.isEmpty() && editAny.isEmpty())) { "--allow-all-edits cannot be combined with positive edit tags" }
    require(capabilities.all { it in SOURCE_ROLE_CAPABILITIES }) { "Unknown source role capability" }
    val editable = if (hasEditSelection) FileTagPredicate(editAll, editAny, editNone) else null
    val options = projectOptions(projectArgs, lockOptions = true)
    require(options.pins.isEmpty() && options.scopedPins.isEmpty() && options.metadataExclusions.isEmpty()) {
        "Source views classify declared files; dependency resolution options are not supported"
    }
    check(kvik_interrupt_install() == 0) { "Cannot install source view interruption handlers" }
    try {
        return withPreparedSourceProject(source, options) { prepared ->
            val manifest = prepared.scopedManifest(::sourceCheckpoint)
            val paths = manifest.files.map { it.path }
            val files = paths.toSet()
            // A source/resource directory must not alias a file, even when its leaf is absent.
            val directories = prepared.project.configuration.project.modules.flatMap { module ->
                module.sourceSets.flatMap { it.directories + it.resources }
            }.distinct()
            for (directory in directories) {
                sourceCheckpoint()
                var path = directory.value
                while (path.isNotEmpty()) {
                    require(LogicalPath(path) !in files) { "Source/resource root is not a directory: ${directory.value}" }
                    path = path.substringBeforeLast('/', "")
                }
            }
            val lock = LogicalPath(options.lock)
            prepared.entry(lock) // Validate topology; an absent lock is omitted from the projection.
            val rules = projectFileTagRules(prepared.project.configuration, prepared.project.name, lock, ::sourceCheckpoint)
            val tagged = paths.map { path -> sourceCheckpoint(); TaggedProjectFile(path, rules.tags(path)) }
                .filter { predicate.matches(it.tags) }
                .map { TaggedProjectFile(prepared.revisionPath(it.path), it.tags) }
            val view = SourceView(name, prepared.reference, predicate, tagged)
            val result = policyName?.let { SourceRolePolicy(it, view, rules, editable, capabilities).toJson() } ?: view.toJson()
            if (!options.offline) NativeContentStore(projectPath(currentDirectory(), options.store)).put(prepared.configuration.bytes)
            sourceCheckpoint()
            result
        }
    } finally { kvik_interrupt_restore() }
}
