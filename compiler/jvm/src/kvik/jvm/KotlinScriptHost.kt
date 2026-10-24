package kvik.jvm

import kvik.core.*
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.StringScriptSource
import kotlin.script.experimental.jvm.*
import kotlin.script.experimental.jvm.impl.KJvmCompiledModuleFromClassLoader
import kotlin.script.experimental.jvm.impl.KJvmCompiledModuleInMemory
import kotlin.script.experimental.jvm.impl.KJvmCompiledScript
import kotlin.script.experimental.jvmhost.JvmScriptCompiler
import kotlin.script.templates.standard.ScriptTemplateWithArgs

/** These annotations constrain the supplied lock and captured sources; they never resolve dependencies. */
@Target(AnnotationTarget.FILE)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
annotation class DependsOn(vararg val coordinates: String)

@Target(AnnotationTarget.FILE)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
annotation class Repository(vararg val urls: String)

@Target(AnnotationTarget.FILE)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
annotation class Import(vararg val paths: String)

data class KotlinScriptCompileRequest(
    val primary: String,
    val sources: Map<String, String>,
    val imports: Map<String, List<String>> = emptyMap(),
    val classpath: List<Path>,
    val options: List<String> = emptyList(),
    val pluginClasspath: List<Path> = emptyList(),
    /** Each entry is the value following -P, e.g. plugin:example.plugin:key=value. */
    val pluginOptions: List<String> = emptyList(),
    val declaredDependencies: Set<String> = emptySet(),
    val declaredRepositories: Set<String> = emptySet(),
    val output: Path,
)

data class KotlinScriptDiagnostic(val code: Int, val severity: String, val message: String,
                                  val source: String? = null, val line: Int? = null, val column: Int? = null)
data class KotlinScriptCompileResult(val successful: Boolean, val diagnostics: List<KotlinScriptDiagnostic>)
data class KotlinScriptEvaluationResult(val successful: Boolean, val diagnostics: List<KotlinScriptDiagnostic>)

/** Genuine K2 scripting, with compilation and evaluation usable in separate owned JVM processes. */
object KotlinScriptHost {
    const val DESCRIPTOR = "kvik-script.json"

    suspend fun compile(request: KotlinScriptCompileRequest): KotlinScriptCompileResult {
        val sourceTexts = request.sources.toMap()
        require(sourceTexts.isNotEmpty() && sourceTexts.size <= 1024 && request.primary in sourceTexts) { "Missing primary script" }
        sourceTexts.forEach { (path, text) ->
            LogicalPath(path)
            require(path.endsWith(".kts") && text.encodeToByteArray().size <= 4 * 1024 * 1024) { "Invalid captured script: $path" }
        }
        require(sourceTexts.values.sumOf { it.encodeToByteArray().size.toLong() } <= 16 * 1024 * 1024) { "Captured scripts exceed 16 MiB" }
        val imported = request.imports.mapValues { it.value.toList() }
        require(imported.keys.all { it in sourceTexts } && imported.values.flatten().all { it in sourceTexts }) { "Import is not a captured script" }
        validateGraph(sourceTexts.keys.associateWith { imported[it].orEmpty().toSet() })
        val declaredDependencies = request.declaredDependencies.toSet()
        val declaredRepositories = request.declaredRepositories.toSet()
        val output = request.output.toAbsolutePath().normalize()
        require(!Files.exists(output, NOFOLLOW_LINKS)) { "Script bundle output already exists" }
        val inputs = request.classpath.map(::classpathEntry)
        val plugins = request.pluginClasspath.map(::classpathEntry)
        val options = checkedOptions(request.options) + plugins.map { "-Xplugin=$it" } + request.pluginOptions.flatMap {
            require(it.matches(Regex("plugin:[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+=.*", RegexOption.DOT_MATCHES_ALL)) && '\u0000' !in it) {
                "Invalid script compiler plugin option"
            }
            listOf("-P", it)
        }
        require(request.pluginOptions.isEmpty() || plugins.isNotEmpty()) { "Plugin options require a declared compiler plugin" }
        // Expose only the three source-retention annotations, never the implementation worker classes.
        val annotationClasses = Files.createTempDirectory("kvik-kts-annotations-")
        try {
            for (type in listOf(DependsOn::class.java, Repository::class.java, Import::class.java)) {
                for (annotation in listOf(type) + type.declaredClasses.toList()) {
                    val name = annotation.name.replace('.', '/') + ".class"
                    val bytes = annotation.getResourceAsStream("/$name")?.use { it.readNBytes(65537) }
                    require(bytes != null && bytes.size <= 65536) { "Missing or oversized pinned script annotation" }
                    val destination = annotationClasses.resolve(name)
                    Files.createDirectories(destination.parent)
                    Files.write(destination, bytes)
                }
            }
            val support = listOf(annotationClasses,
                classpathEntry(Path.of(ScriptTemplateWithArgs::class.java.protectionDomain.codeSource.location.toURI())))
            val sources = sourceTexts.mapValues { (path, text) -> StringScriptSource(text, path.substringAfterLast('/'), path) }
            val annotationImports = mutableMapOf<String, List<String>>()
            val config = ScriptCompilationConfiguration {
                baseClass(ScriptTemplateWithArgs::class)
                defaultImports(DependsOn::class, Repository::class, Import::class)
                jvm { dependencies(JvmDependency((inputs + support).distinct().map(Path::toFile))) }
                compilerOptions(options)
                refineConfiguration {
                    onAnnotations(DependsOn::class, Repository::class, Import::class, handler = { context ->
                        val path = context.script.locationId
                        val diagnostics = mutableListOf<ScriptDiagnostic>()
                        val paths = mutableListOf<String>()
                        for (found in context.collectedData?.get(ScriptCollectedData.collectedAnnotations).orEmpty()) {
                            fun refuse(message: String) {
                                diagnostics += ScriptDiagnostic(ScriptDiagnostic.unspecifiedError, message,
                                    ScriptDiagnostic.Severity.ERROR, found.location)
                            }
                            // Kotlin can instantiate collected annotations in a compiler dependency loader.
                            // Their verified names/accessors cross that loader boundary; Kotlin `is` does not.
                            val annotation = found.annotation
                            fun values(property: String): List<String> {
                                val value = annotation.annotationClass.java.getMethod(property).invoke(annotation) as Array<*>
                                require(value.all { it is String }) { "Invalid script annotation values" }
                                return value.map { it as String }
                            }
                            when (annotation.annotationClass.java.name) {
                                "kvik.jvm.DependsOn" -> for (coordinate in values("coordinates")) if (coordinate !in declaredDependencies)
                                    refuse("@DependsOn is not present in the declared dependency lock: $coordinate")
                                "kvik.jvm.Repository" -> for (url in values("urls")) if (url !in declaredRepositories)
                                    refuse("@Repository is not declared in the configuration: $url")
                                "kvik.jvm.Import" -> for (reference in values("paths")) {
                                    val resolved = try { relativeImport(requireNotNull(path), reference) }
                                        catch (failure: IllegalArgumentException) { refuse(failure.message ?: "Invalid script import"); null }
                                    if (resolved != null) {
                                        if (resolved !in sources) refuse("@Import is not a captured source: $reference")
                                        else paths += resolved
                                    }
                                }
                            }
                        }
                        if (path != null) annotationImports[path] = paths.distinct()
                        if (diagnostics.isEmpty()) context.compilationConfiguration.asSuccess()
                        else ResultWithDiagnostics.Failure(diagnostics)
                    })
                    beforeCompiling { context ->
                        val path = context.script.locationId
                        require(path in sources) { "Compiler requested an uncaptured script: $path" }
                        context.compilationConfiguration.with {
                            // Refine each script independently; otherwise helpers inherit an import of themselves.
                            importScripts((imported[path].orEmpty() + annotationImports[path].orEmpty()).distinct().map { sources.getValue(it) })
                        }.asSuccess()
                    }
                }
            }
            val result = JvmScriptCompiler()(sources.getValue(request.primary), config)
            val diagnostics = result.reports.filter { it.severity >= ScriptDiagnostic.Severity.WARNING }.map(::diagnostic)
            if (result !is ResultWithDiagnostics.Success) return KotlinScriptCompileResult(false, diagnostics)
            val script = result.value as? KJvmCompiledScript ?: error("Unsupported Kotlin compiled script representation")
            val module = script.getCompiledModule() as? KJvmCompiledModuleInMemory ?: error("Expected in-memory Kotlin script classes")
            val files = module.compilerOutputFiles.toMap()
            require(files.isNotEmpty() && DESCRIPTOR !in files) { "Invalid Kotlin script output" }
            files.keys.forEach(::LogicalPath)
            // Compiler state contains absolute classpaths. Persist only bytecode and the constructor dependency tree.
            val descriptor = obj("schemaVersion" to 1.json(), "kind" to "kvik-kotlin-script".json(), "script" to descriptor(script))
            Files.createDirectories(output)
            for ((path, bytes) in files.toSortedMap()) {
                val destination = output.resolve(path)
                Files.createDirectories(destination.parent)
                Files.write(destination, bytes)
            }
            Files.writeString(output.resolve(DESCRIPTOR), descriptor.canonical() + "\n")
            return KotlinScriptCompileResult(true, diagnostics)
        } finally {
            Files.walk(annotationClasses).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    /** Runtime classpath must explicitly include the pinned stdlib/script runtime and locked project dependencies. */
    suspend fun evaluate(bundle: Path, classpath: List<Path>, arguments: List<String>): KotlinScriptEvaluationResult {
        val root = classpathEntry(bundle)
        require(Files.isDirectory(root, NOFOLLOW_LINKS)) { "Script bundle must be a directory" }
        val metadata = root.resolve(DESCRIPTOR)
        require(Files.isRegularFile(metadata, NOFOLLOW_LINKS) && Files.size(metadata) <= 4 * 1024 * 1024) { "Missing or oversized script descriptor" }
        val fields = parseJson(Files.readString(metadata)).fields(setOf("schemaVersion", "kind", "script"))
        fields.requireVersion()
        require(fields.getValue("kind").stringValue() == "kvik-kotlin-script")
        val paths = listOf(root) + classpath.map(::classpathEntry)
        // The worker/compiler loader is deliberately excluded from script visibility.
        URLClassLoader(paths.distinct().map { it.toUri().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
            val script = restore(fields.getValue("script"), loader)
            val result = BasicJvmScriptEvaluator()(script, ScriptEvaluationConfiguration {
                constructorArgs(arguments.toTypedArray())
                scriptsInstancesSharing(true)
                jvm { baseClassLoader(loader); loadDependencies(false) }
            })
            val diagnostics = result.reports.filter { it.severity >= ScriptDiagnostic.Severity.WARNING }.map(::diagnostic).toMutableList()
            val error = (result as? ResultWithDiagnostics.Success)?.value?.returnValue as? ResultValue.Error
            if (error != null) diagnostics += KotlinScriptDiagnostic(ScriptDiagnostic.unspecifiedError, "error",
                error.error.toString(), script.sourceLocationId)
            return KotlinScriptEvaluationResult(result is ResultWithDiagnostics.Success && error == null, diagnostics)
        }
    }

    private fun descriptor(script: KJvmCompiledScript): JsonValue.Object = obj(
        "source" to requireNotNull(script.sourceLocationId).also(::LogicalPath).json(),
        "className" to script.scriptClassFQName.json(),
        "imports" to script.otherScripts.map { descriptor(it as KJvmCompiledScript) }.json(),
    )

    private fun restore(value: JsonValue, loader: ClassLoader, depth: Int = 0): KJvmCompiledScript {
        require(depth <= 128) { "Script import tree too deep" }
        val fields = value.fields(setOf("source", "className", "imports"))
        val source = fields.getValue("source").stringValue().also(::LogicalPath)
        val name = fields.getValue("className").stringValue()
        require(name.isNotBlank() && name.none { it in "/\\;[" || it.code < 32 }) { "Invalid script class name" }
        val imports = fields.getValue("imports").arrayValue()
        require(imports.size <= 1024)
        return KJvmCompiledScript(source, ScriptCompilationConfiguration {}, name, null,
            imports.map { restore(it, loader, depth + 1) }, KJvmCompiledModuleFromClassLoader(loader))
    }

    private fun diagnostic(value: ScriptDiagnostic) = KotlinScriptDiagnostic(value.code, value.severity.name.lowercase(),
        value.message, value.sourcePath, value.location?.start?.line, value.location?.start?.col)

    private fun relativeImport(source: String, reference: String): String {
        require(reference.isNotEmpty() && !reference.startsWith('/') && '\\' !in reference && ':' !in reference) { "@Import requires a relative captured path" }
        val components = source.split('/').dropLast(1).toMutableList()
        for (part in reference.split('/')) when (part) {
            "", "." -> require(part == ".") { "Empty script import path component" }
            ".." -> { require(components.isNotEmpty()) { "Script import escapes captured root" }; components.removeAt(components.lastIndex) }
            else -> components += part
        }
        return components.joinToString("/").also(::LogicalPath)
    }

    private fun classpathEntry(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        require(!Files.isSymbolicLink(absolute) && (Files.isRegularFile(absolute, NOFOLLOW_LINKS) || Files.isDirectory(absolute, NOFOLLOW_LINKS))) {
            "Missing or unsafe script classpath: $path"
        }
        return absolute
    }

    private fun checkedOptions(options: List<String>): List<String> {
        val result = mutableListOf<String>()
        var index = 0
        while (index < options.size) {
            val option = options[index++]
            val name = option.substringBefore('=')
            if (name == "-Werror") { require(name == option); result += option; continue }
            require(name in setOf("-jvm-target", "-Xjdk-release", "-module-name", "-language-version", "-api-version", "-opt-in", "-Xexplicit-api")) {
                "Unsupported script compiler option: $name"
            }
            val value = if ('=' in option) option.substringAfter('=') else options.getOrNull(index++) ?: error("Missing script compiler option value")
            require(value.isNotBlank() && !value.startsWith('-') && '\u0000' !in value) { "Invalid script compiler option value" }
            result += if ('=' in option) listOf(option) else listOf(name, value)
        }
        return result
    }
}
