package kvik.cli

import kvik.core.NativeTarget

/** Physical roots and libraries have already been prepared and checked by the export boundary. */
internal data class IdeaRoot(val path: String, val resource: Boolean = false, val generated: Boolean = false)
internal data class IdeaLibrary(val name: String, val path: String, val common: Boolean = false, val target: String = if (common) "common" else "jvm")
internal sealed interface IdeaPlatform {
    data class Jvm(val target: Int) : IdeaPlatform {
        init { require(target in setOf(11, 17, 21, 25)) }
    }
    data class Web(val target: String) : IdeaPlatform {
        init { require(target in setOf("js", "wasmJs")) }
    }
    /** The intersection of the actual consuming targets, including intermediate source sets. */
    data class Common(val targets: Set<String>, val jvmTarget: Int = 17) : IdeaPlatform {
        init {
            require(targets.size >= 2 && targets.all { it in setOf("jvm", "js", "wasmJs") || NativeTarget.find(it) != null })
            Jvm(jvmTarget)
        }
    }
}
internal data class IdeaCompilation(
    val name: String,
    val roots: List<IdeaRoot>,
    val modules: List<String>,
    val libraries: List<IdeaLibrary>,
    val platform: IdeaPlatform,
    val compilerArguments: List<String>,
    val friend: String? = null,
    val test: Boolean = false,
    val refines: List<String> = emptyList(),
    val friends: List<String> = emptyList(),
)
internal data class IdeaRun(val name: String, val arguments: List<String>, val directory: String)

/** A complete independent IDEA project. Source files stay in their original workspace. */
internal fun ideaProjectFiles(sdk: String, compilations: List<IdeaCompilation>, runs: List<IdeaRun>): Map<String, String> {
    require(sdk.isNotBlank() && compilations.isNotEmpty())
    val names = compilations.map { it.name }.toSet()
    require(names.size == compilations.size && names.all { it.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*")) })
    val byName = compilations.associateBy { it.name }
    fun targets(c: IdeaCompilation): Set<String> = when (val platform = c.platform) {
        is IdeaPlatform.Jvm -> setOf("jvm")
        is IdeaPlatform.Common -> platform.targets
        is IdeaPlatform.Web -> setOf(platform.target)
    }
    // IDEA needs the complete refinement closure, with ordinary module edges as well.
    // Keep ordinary library dependencies separate: they must not grant internal visibility.
    fun refinements(c: IdeaCompilation): List<String> {
        val reached = linkedSetOf<String>()
        val visiting = mutableSetOf(c.name)
        fun visit(owner: IdeaCompilation) {
            require(owner.refines.distinct().size == owner.refines.size)
            for (name in owner.refines) {
                val parent = byName[name] ?: error("Unknown IDEA refinement: $name")
                require(parent.platform is IdeaPlatform.Common && targets(parent).containsAll(targets(owner))) {
                    "IDEA refinement has incompatible targets: ${owner.name} -> $name"
                }
                require(visiting.add(name)) { "Cyclic IDEA refinement: $name" }
                if (reached.add(name)) visit(parent)
                visiting.remove(name)
            }
        }
        visit(c)
        return reached.toList()
    }
    val refinementClosures = compilations.associate { it.name to refinements(it) }
    require(runs.map { it.name }.distinct().size == runs.size)
    val files = linkedMapOf<String, String>()
    files[".idea/modules.xml"] = ideaXml("project", "version" to "4") {
        element("component", "name" to "ProjectModuleManager") {
            element("modules") {
                compilations.forEach { c ->
                    val path = "\$PROJECT_DIR\$/.idea/modules/${c.name}.iml"
                    element("module", "fileurl" to "file://$path", "filepath" to path)
                }
            }
        }
    }
    files[".idea/misc.xml"] = ideaXml("project", "version" to "4") {
        element("component", "name" to "ProjectRootManager", "version" to "2",
            "project-jdk-name" to sdk, "project-jdk-type" to "JavaSDK")
    }
    for (c in compilations) {
        val jvm = c.platform as? IdeaPlatform.Jvm
        val common = c.platform as? IdeaPlatform.Common
        val web = c.platform as? IdeaPlatform.Web
        val refined = refinementClosures.getValue(c.name)
        val multiplatform = common != null || refined.isNotEmpty()
        val friends = (listOfNotNull(c.friend) + c.friends).distinct()
        val moduleEdges = (c.modules + refined + c.friends).distinct()
        require(c.modules.distinct().size == c.modules.size && c.modules.all { it in names && it != c.name })
        if (common != null) require(c.modules.all { dependency ->
            val other = byName.getValue(dependency)
            other.platform is IdeaPlatform.Common && targets(other).containsAll(common.targets)
        }) { "Common IDEA sources cannot depend on platform-specific modules" }
        require(friends.all { it in moduleEdges && it in names && it != c.name })
        require(c.roots.map { it.path }.distinct().size == c.roots.size)
        c.roots.forEach { ideaPath(it.path) }
        c.libraries.forEach {
            ideaPath(it.path)
            require(it.common == (common != null) && it.target == (if (common != null) "common" else web?.target ?: "jvm") &&
                it.path.endsWith(if (jvm != null) ".jar" else ".klib")) {
                "IDEA libraries must match the source platform"
            }
        }
        files[".idea/modules/${c.name}.iml"] = ideaXml("module", "type" to "JAVA_MODULE", "version" to "4") {
            element("component", "name" to "FacetManager") {
                element("facet", "type" to "kotlin-language", "name" to "Kotlin") {
                    val platforms = common?.targets?.map { target -> when (target) {
                        "jvm" -> "JVM [${common.jvmTarget}]"
                        "js" -> "JS []"
                        "wasmJs" -> "wasm-js [JS]"
                        else -> "Native [${NativeTarget.require(target).compilerName}]"
                    } }?.let { components -> components + if (common.targets.any { NativeTarget.find(it) != null }) listOf("Native []") else emptyList() }
                        ?.sorted()?.joinToString("/") ?: when (web?.target) {
                            "js" -> "JS []"
                            "wasmJs" -> "wasm-js [JS]"
                            else -> "JVM [${checkNotNull(jvm).target}]"
                        }
                    val attributes = listOf("version" to "5", "platform" to when {
                        common != null -> "Common (experimental) "
                        web?.target == "js" -> "JavaScript "
                        web?.target == "wasmJs" -> "Wasm"
                        else -> "JVM ${jvm!!.target}"
                    },
                        "allPlatforms" to platforms, "useProjectSettings" to "false") +
                        if (multiplatform) listOf("isHmppProject" to "true", "isTestModule" to c.test.toString()) else emptyList()
                    element("configuration", *attributes.toTypedArray()) {
                        if (multiplatform) textElement("newMppModelJpsModuleKind",
                            if (common != null) "SOURCE_SET_HOLDER" else "COMPILATION_AND_SOURCE_SET_HOLDER")
                        if (refined.isNotEmpty()) element("dependsOnModuleNames") {
                            refined.forEach { textElement("dependsOn", it) }
                        }
                        if (friends.size == 1) textElement("additionalVisibleModuleNames", friends.single())
                        else if (friends.isNotEmpty()) element("additionalVisibleModuleNames") {
                            friends.forEach { textElement("friend", it) }
                        }
                        element("compilerSettings") {
                            element("option", "name" to "additionalArguments", "value" to c.compilerArguments.joinToString(" ", transform = ::ideaArgument))
                        }
                        element("compilerArguments") {
                            element("stringArguments") {
                                jvm?.let { element("stringArg", "name" to "jvmTarget", "arg" to it.target.toString()) }
                                if (web?.target == "wasmJs") element("stringArg", "name" to "wasmTarget", "arg" to "wasm-js")
                                element("stringArg", "name" to "languageVersion", "arg" to "2.4")
                                element("stringArg", "name" to "apiVersion", "arg" to "2.4")
                            }
                            if (multiplatform) element("booleanArguments") {
                                element("booleanArg", "name" to "multiPlatform", "arg" to "true")
                            }
                        }
                    }
                }
            }
            val rootAttributes = listOf("name" to "NewModuleRootManager", "inherit-compiler-output" to "true") +
                (jvm?.let { listOf("LANGUAGE_LEVEL" to "JDK_${it.target}") } ?: emptyList())
            element("component", *rootAttributes.toTypedArray()) {
                element("exclude-output")
                for (root in c.roots) element("content", "url" to "file://${root.path}") {
                    val attributes = mutableListOf("url" to "file://${root.path}")
                    if (root.resource) attributes += "type" to if (c.test) "java-test-resource" else "java-resource"
                    else attributes += "isTestSource" to c.test.toString()
                    if (root.generated) attributes += "generated" to "true"
                    element("sourceFolder", *attributes.toTypedArray())
                }
                if (jvm != null) element("orderEntry", "type" to "inheritedJdk")
                element("orderEntry", "type" to "sourceFolder", "forTests" to "false")
                // Each compilation contains its exact visibility closure. Re-exporting these edges
                // would expose private dependencies to other compilations through IDEA traversal.
                moduleEdges.forEach { element("orderEntry", "type" to "module", "module-name" to it) }
                for (library in c.libraries) element("orderEntry", "type" to "module-library") {
                    val kind = when (library.target) {
                        "common" -> "kotlin.common"
                        "js" -> "kotlin.js"
                        "wasmJs" -> "kotlin.wasm.js"
                        else -> null
                    }
                    val libraryAttributes = listOf("name" to library.name) + if (kind != null) listOf("type" to kind) else emptyList()
                    element("library", *libraryAttributes.toTypedArray()) {
                        element("CLASSES") { element("root", "url" to "jar://${library.path}!/") }
                        element("JAVADOC")
                        element("SOURCES")
                    }
                }
            }
        }
    }
    for ((index, run) in runs.withIndex()) {
        require(run.arguments.isNotEmpty())
        ideaPath(run.directory)
        val script = ".idea/kvik-command-$index.sh"
        files[script] = "#!/bin/sh\ncd ${shellArgument(run.directory)} || exit 1\nexec ${run.arguments.joinToString(" ", transform = ::shellArgument)}\n"
        files[".idea/runConfigurations/kvik-$index.xml"] = ideaXml("component", "name" to "ProjectRunConfigurationManager") {
            element("configuration", "default" to "false", "name" to run.name, "type" to "ShConfigurationType") {
                element("option", "name" to "SCRIPT_PATH", "value" to "\$PROJECT_DIR\$/$script")
                element("option", "name" to "SCRIPT_WORKING_DIRECTORY", "value" to run.directory)
                element("option", "name" to "INTERPRETER_PATH", "value" to "/bin/sh")
                element("option", "name" to "EXECUTE_IN_TERMINAL", "value" to "false")
                element("option", "name" to "EXECUTE_SCRIPT_FILE", "value" to "true")
                element("method", "v" to "2")
            }
        }
    }
    return files
}

private fun ideaPath(path: String) {
    require(path.startsWith('/') && !path.contains(Regex("\\$[A-Za-z_][A-Za-z0-9_]*\\$"))) { "IDE roots require absolute paths without IDE macros" }
    ideaEscape(path)
}

private fun ideaArgument(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
private fun shellArgument(value: String): String {
    require('\u0000' !in value)
    return "'${value.replace("'", "'\\''")}'"
}

private fun ideaEscape(value: String): String = buildString {
    for (c in value) {
        require(c.code >= 0x20 && c != '\uFFFE' && c != '\uFFFF') { "IDE XML contains a control character" }
        append(when (c) { '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"; '\'' -> "&apos;"; else -> c.toString() })
    }
}

private class IdeaXml {
    private val out = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    fun element(name: String, vararg attributes: Pair<String, String>, body: (IdeaXml.() -> Unit)? = null) {
        out.append('<').append(name)
        for ((key, value) in attributes) out.append(' ').append(key).append("=\"").append(ideaEscape(value)).append('"')
        if (body == null) out.append(" />\n") else {
            out.append(">\n"); body(); out.append("</").append(name).append(">\n")
        }
    }
    fun textElement(name: String, value: String) { out.append('<').append(name).append('>').append(ideaEscape(value)).append("</").append(name).append(">\n") }
    override fun toString() = out.toString()
}

private fun ideaXml(name: String, vararg attributes: Pair<String, String>, body: IdeaXml.() -> Unit): String =
    IdeaXml().apply { element(name, *attributes, body = body) }.toString()
