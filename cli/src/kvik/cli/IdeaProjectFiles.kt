package kvik.cli

/** Physical roots and libraries have already been prepared and checked by the export boundary. */
internal data class IdeaRoot(val path: String, val resource: Boolean = false, val generated: Boolean = false)
internal data class IdeaLibrary(val name: String, val path: String)
internal data class IdeaCompilation(
    val name: String,
    val roots: List<IdeaRoot>,
    val modules: List<String>,
    val libraries: List<IdeaLibrary>,
    val jvmTarget: Int,
    val compilerArguments: List<String>,
    val friend: String? = null,
    val test: Boolean = false,
)
internal data class IdeaRun(val name: String, val arguments: List<String>, val directory: String)

/** A complete independent IDEA project. Source files stay in their original workspace. */
internal fun ideaProjectFiles(sdk: String, compilations: List<IdeaCompilation>, runs: List<IdeaRun>): Map<String, String> {
    require(sdk.isNotBlank() && compilations.isNotEmpty())
    val names = compilations.map { it.name }.toSet()
    require(names.size == compilations.size && names.all { it.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*")) })
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
        require(c.jvmTarget in setOf(11, 17, 21, 25))
        require(c.modules.distinct().size == c.modules.size && c.modules.all { it in names && it != c.name })
        require(c.friend == null || c.friend in c.modules)
        require(c.roots.map { it.path }.distinct().size == c.roots.size)
        c.roots.forEach { ideaPath(it.path) }
        c.libraries.forEach { ideaPath(it.path); require(it.path.endsWith(".jar")) }
        files[".idea/modules/${c.name}.iml"] = ideaXml("module", "type" to "JAVA_MODULE", "version" to "4") {
            element("component", "name" to "FacetManager") {
                element("facet", "type" to "kotlin-language", "name" to "Kotlin") {
                    element("configuration", "version" to "5", "platform" to "JVM ${c.jvmTarget}",
                        "allPlatforms" to "JVM [${c.jvmTarget}]", "useProjectSettings" to "false") {
                        c.friend?.let { textElement("additionalVisibleModuleNames", it) }
                        element("compilerSettings") {
                            element("option", "name" to "additionalArguments", "value" to c.compilerArguments.joinToString(" ", transform = ::ideaArgument))
                        }
                        element("compilerArguments") {
                            element("stringArguments") {
                                element("stringArg", "name" to "jvmTarget", "arg" to c.jvmTarget.toString())
                                element("stringArg", "name" to "languageVersion", "arg" to "2.4")
                                element("stringArg", "name" to "apiVersion", "arg" to "2.4")
                            }
                        }
                    }
                }
            }
            element("component", "name" to "NewModuleRootManager", "LANGUAGE_LEVEL" to "JDK_${c.jvmTarget}", "inherit-compiler-output" to "true") {
                element("exclude-output")
                for (root in c.roots) element("content", "url" to "file://${root.path}") {
                    val attributes = mutableListOf("url" to "file://${root.path}")
                    if (root.resource) attributes += "type" to if (c.test) "java-test-resource" else "java-resource"
                    else attributes += "isTestSource" to c.test.toString()
                    if (root.generated) attributes += "generated" to "true"
                    element("sourceFolder", *attributes.toTypedArray())
                }
                element("orderEntry", "type" to "inheritedJdk")
                element("orderEntry", "type" to "sourceFolder", "forTests" to "false")
                // Each compilation contains its exact visibility closure. Re-exporting these edges
                // would expose private dependencies to other compilations through IDEA traversal.
                c.modules.forEach { element("orderEntry", "type" to "module", "module-name" to it) }
                for (library in c.libraries) element("orderEntry", "type" to "module-library") {
                    element("library", "name" to library.name) {
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
