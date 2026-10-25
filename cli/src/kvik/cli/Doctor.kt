@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import platform.posix.X_OK
import platform.posix.access

/** Inspect the same installed bytes and layouts used by execution, without launching tools. */
internal fun doctorCommand(args: List<String>): ExitStatus {
    var toolsPath: String? = null
    var mode = "build"
    var modeSeen = false
    val targets = linkedSetOf<String>()
    fun arguments(): Nothing = throw CliFailure("KVIK_DOCTOR_ARGUMENTS", DiagnosticCategory.CONFIGURATION,
        "Use doctor --tools PATH --target TARGET (repeatable) [--check build|test]", ExitStatus.INVALID_CONFIGURATION)
    var index = 0
    while (index < args.size) {
        val option = args[index++]
        val value = args.getOrNull(index++)?.takeIf { it.isNotBlank() } ?: arguments()
        when (option) {
            "--tools" -> { if (toolsPath != null) arguments(); toolsPath = value }
            "--target" -> {
                if (value != "jvm" && NativeTarget.find(value) == null && KotlinJsTarget.find(value) == null) arguments()
                targets += value
            }
            "--check" -> {
                if (modeSeen || value !in setOf("build", "test")) arguments()
                modeSeen = true
                mode = value
            }
            else -> arguments()
        }
    }
    if (toolsPath == null || targets.isEmpty()) arguments()
    val path = projectPath(currentDirectory(), toolsPath)
    val checks = mutableListOf<JsonValue>()
    var failed = false
    fun check(id: String, target: String? = null, available: Boolean = true, block: () -> Unit): Boolean {
        val diagnostic = if (!available) Diagnostic("KVIK_DOCTOR_DEPENDENCY_FAILED", DiagnosticCategory.TOOLCHAIN,
            "Tools declaration check failed; this check was not performed", target = target)
        else try {
            block()
            null
        } catch (failure: Exception) {
            Diagnostic(if (failure is RuntimeIntegrityFailure) "KVIK_RUNTIME_INTEGRITY" else "KVIK_DOCTOR_CHECK_FAILED",
                DiagnosticCategory.TOOLCHAIN, failure.message ?: "Tool check failed", target = target)
        }
        if (diagnostic != null) failed = true
        checks += obj("id" to id.json(), "target" to (target?.json() ?: JsonValue.Null),
            "status" to (if (!available) "skipped" else if (diagnostic == null) "passed" else "failed").json(),
            "diagnostic" to (diagnostic?.toJson() ?: JsonValue.Null))
        return diagnostic == null
    }
    check("runtime-guardian") {
        val guardian = verifiedRuntimeGuardian()
        val info = NativeFiles.info(guardian) ?: error("Missing runtime guardian")
        require(NativeFiles.regular(info) && info.mode and 73 != 0 && access(guardian, X_OK) == 0) {
            "Runtime guardian must be a regular executable accessible to the current user"
        }
    }
    var tools: BuildTools? = null
    check("tools-declaration") { tools = BuildTools(path) }
    val installed = tools
    for (target in targets.sorted()) {
        check("compilation-tools", target, installed != null) {
            val selected = requireNotNull(installed)
            val identities = selected.desiredIdentities(setOf(target)).getValue(target)
            val native = NativeTarget.find(target)
            if (native != null) selected.nativeProvider(native).verified(identities)
            else selected.jvmProvider().verified(identities)
        }
        if (mode == "test") check("test-runtime", target, installed != null) {
            val selected = requireNotNull(installed)
            val native = NativeTarget.find(target)
            when {
                KotlinJsTarget.find(target) != null -> {
                    val identities = selected.desiredIdentities(setOf(target), setOf(target)).getValue(target)
                    selected.nodeProvider().verified(identities.getValue("node")) {}
                }
                native != null -> require(native.supportsHostExecution &&
                    (if (native.isApple) selected.declaration.host.os == "Darwin" else selected.declaration.host.os == "Linux")) {
                    "Host test execution is unsupported for $target on ${selected.declaration.host.os}"
                }
                else -> selected.jvmProvider().verifiedExecution(selected.desiredIdentities(setOf(target)).getValue(target)) {}
            }
        }
    }
    println(obj("schemaVersion" to CONTRACT_VERSION.json(), "status" to (if (failed) "incomplete" else "passed").json(),
        "scope" to "tool-installations".json(), "tools" to path.json(), "check" to mode.json(),
        "targets" to targets.sorted().map { it.json() }.json(), "checks" to checks.json()).canonical())
    return if (failed) ExitStatus.INCOMPLETE else ExitStatus.SUCCESS
}
