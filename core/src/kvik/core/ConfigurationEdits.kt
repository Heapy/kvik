package kvik.core

/**
 * Apply an atomic, declarative edit batch to the authoring document without reserializing its model.
 * Unrelated fields and omitted defaults remain exactly as authored. Operation envelopes and lookup
 * identities are checked immediately; the existing project parser validates the final document once,
 * so coordinated edits may pass through temporarily invalid dependency/target graphs.
 */
fun editProjectConfiguration(configuration: ProjectConfiguration, edits: JsonValue): ProjectConfiguration {
    val batch = edits.fields(setOf("schemaVersion", "operations"))
    batch.requireVersion()
    val operations = batch.getValue("operations").arrayValue()
    require(operations.size in 1..128) { "Configuration edits require 1 to 128 operations" }
    var document = configuration.document
    for (operation in operations) {
        val name = operation.objectValue().getValue("op").stringValue()
        val collection = when (name) {
            "set-catalog-entry", "set-catalog-version", "remove-catalog-entry" -> "dependencyCatalog"
            "set-dependency-profile", "remove-dependency-profile" -> "dependencyProfiles"
            "set-script", "remove-script" -> "scripts"
            "set-plugin-settings", "remove-plugin-settings" -> "pluginSettings"
            else -> null
        }
        if (collection != null) {
            document = editNamedConfiguration(document, operation, name, collection)
            continue
        }
        val fields = operation.fields(when (name) {
            "set-dependency" -> setOf("op", "module", "sourceSet", "dependency")
            "set-dependency-version" -> setOf("op", "module", "sourceSet", "package", "version")
            "remove-dependency" -> setOf("op", "module", "sourceSet", "selector")
            "set-target", "remove-target" -> setOf("op", "module", "target")
            "set-source-set", "remove-source-set" -> setOf("op", "module", "sourceSet")
            else -> throw IllegalArgumentException("Unknown configuration edit operation: $name")
        })
        val moduleId = fields.getValue("module").stringValue()
        val modules = document.values.getValue("modules").arrayValue().toMutableList()
        val moduleIndex = editIndex(modules, "id", moduleId, "module")
        val module = modules[moduleIndex].objectValue()
        val entryField = when (name) {
            "set-target", "remove-target" -> "target"
            "set-source-set", "remove-source-set" -> "sourceSet"
            else -> null
        }
        val changedModule = if (entryField != null) {
            val entry = fields.getValue(entryField)
            val removing = name.startsWith("remove-")
            val entryName = if (removing) entry.stringValue() else entry.objectValue().getValue("name").stringValue()
            val collectionName = if (entryField == "target") "targets" else "sourceSets"
            val entries = module.getValue(collectionName).arrayValue().toMutableList()
            val found = entries.indices.filter { entries[it].objectValue().getValue("name").stringValue() == entryName }
            require(found.size <= 1) { "Ambiguous $entryField $moduleId:$entryName" }
            if (removing) {
                require(found.size == 1) { "Unknown $entryField $moduleId:$entryName" }
                entries.removeAt(found.single())
            } else if (found.isEmpty()) entries += entry else entries[found.single()] = entry
            JsonValue.Object(module + (collectionName to entries.json()))
        } else {
            val sourceName = fields.getValue("sourceSet").stringValue()
            val sourceSets = module.getValue("sourceSets").arrayValue().toMutableList()
            val sourceIndex = editIndex(sourceSets, "name", sourceName, "source set in $moduleId")
            val source = sourceSets[sourceIndex].objectValue()
            val dependencies = source.getValue("dependencies").arrayValue().toMutableList()
            val key = when (name) {
                "set-dependency" -> editDependencyKey(fields.getValue("dependency"))
                "set-dependency-version" -> editPackageKey(fields.getValue("package").stringValue())
                else -> {
                    val selector = fields.getValue("selector").fields(emptySet(), setOf("package", "module"))
                    require(selector.size == 1) { "Dependency selector requires exactly one package or module" }
                    selector["package"]?.let { editPackageKey(it.stringValue()) }
                        ?: EditDependencyKey("module", selector.getValue("module").stringValue())
                }
            }
            val found = dependencies.indices.filter { editDependencyKey(dependencies[it]) == key }
            require(found.size <= 1) { "Ambiguous dependency ${key.value} in $moduleId:$sourceName" }
            when (name) {
                "set-dependency" -> if (found.isEmpty()) dependencies += fields.getValue("dependency")
                    else dependencies[found.single()] = fields.getValue("dependency")
                "set-dependency-version" -> {
                    require(found.size == 1) { "Unknown dependency ${key.value} in $moduleId:$sourceName" }
                    val coordinate = Coordinate.parse("${key.value}:${fields.getValue("version").stringValue()}")
                    dependencies[found.single()] = JsonValue.Object(dependencies[found.single()].objectValue() +
                        ("coordinate" to coordinate.toString().json()))
                }
                "remove-dependency" -> {
                    require(found.size == 1) { "Unknown dependency ${key.value} in $moduleId:$sourceName" }
                    dependencies.removeAt(found.single())
                }
            }
            sourceSets[sourceIndex] = JsonValue.Object(source + ("dependencies" to dependencies.json()))
            JsonValue.Object(module + ("sourceSets" to sourceSets.json()))
        }
        modules[moduleIndex] = changedModule
        document = JsonValue.Object(document.values + ("modules" to modules.json()))
    }
    require(document.canonical().encodeToByteArray().size <= 4 * 1024 * 1024) { "Edited project configuration exceeds 4 MiB" }
    return parseProjectConfiguration(document)
}

private fun editNamedConfiguration(
    document: JsonValue.Object, operation: JsonValue, name: String, collection: String,
): JsonValue.Object {
    val identityField = when (collection) {
        "dependencyCatalog" -> "alias"
        "pluginSettings" -> "plugin"
        else -> "name"
    }
    val payloadField = when (name) {
        "set-catalog-entry" -> "coordinate"
        "set-catalog-version" -> "version"
        "set-dependency-profile" -> "profile"
        "set-script" -> "script"
        "set-plugin-settings" -> "settings"
        else -> null
    }
    val fields = operation.fields(setOf("op", identityField) + listOfNotNull(payloadField))
    val identity = fields.getValue(identityField).stringValue()
    val entries = document.values[collection]?.objectValue().orEmpty()
    val changed = if (payloadField == null) {
        require(identity in entries) { "Unknown $collection entry: $identity" }
        entries - identity
    } else {
        val replacement = if (name == "set-catalog-version") {
            val previous = entries[identity] ?: error("Unknown dependencyCatalog entry: $identity")
            val coordinate = Coordinate.parse(previous.stringValue())
            Coordinate.parse("${coordinate.group}:${coordinate.name}:${fields.getValue("version").stringValue()}").toString().json()
        } else fields.getValue(payloadField)
        entries + (identity to replacement)
    }
    return JsonValue.Object(document.values + (collection to JsonValue.Object(changed)))
}

private data class EditDependencyKey(val kind: String, val value: String)

private fun editDependencyKey(value: JsonValue): EditDependencyKey {
    val fields = value.objectValue()
    require(("coordinate" in fields) != ("module" in fields)) { "Dependency requires exactly one module or coordinate identity" }
    return fields["coordinate"]?.let {
        val coordinate = Coordinate.parse(it.stringValue())
        EditDependencyKey("package", "${coordinate.group}:${coordinate.name}")
    } ?: EditDependencyKey("module", fields.getValue("module").stringValue())
}

private fun editPackageKey(value: String): EditDependencyKey {
    val coordinate = Coordinate.parse("$value:0")
    return EditDependencyKey("package", "${coordinate.group}:${coordinate.name}")
}

private fun editIndex(values: List<JsonValue>, field: String, identity: String, label: String): Int {
    val found = values.indices.filter { values[it].objectValue().getValue(field).stringValue() == identity }
    require(found.size == 1) { if (found.isEmpty()) "Unknown $label: $identity" else "Ambiguous $label: $identity" }
    return found.single()
}
