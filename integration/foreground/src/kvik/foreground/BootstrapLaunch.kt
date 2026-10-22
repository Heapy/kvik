package kvik.foreground

import kvik.core.*

enum class BootstrapQuery(val argument: String) {
    COMPOSITION("--print-composition"), LAUNCH("--print-launch")
}

/** Trusted invocation context, not an authenticity check or executable verification. */
class BootstrapLaunch private constructor(
    val sourceRoot: String,
    val python: String,
    val launcher: String,
    private val arguments: List<String>,
    val composition: Digest,
    val runtime: String,
) {
    /** Invoke this argv directly through the owned process host, never a shell. */
    fun command(query: BootstrapQuery): List<String> = listOf(python, launcher) + arguments + query.argument

    fun requireCurrent(expectedComposition: Digest, expectedRuntime: String) {
        require(composition == expectedComposition && runtime == expectedRuntime) {
            "Bootstrap context does not identify this running executable"
        }
    }

    /** A query may select new sources, but must not change invocation policy or source ownership. */
    fun requireReplacement(previous: BootstrapLaunch, expectedComposition: Digest) {
        require(sourceRoot == previous.sourceRoot && python == previous.python && launcher == previous.launcher &&
            arguments == previous.arguments && composition == expectedComposition) {
            "Bootstrap replacement does not match the requested composition and launch policy"
        }
    }

    companion object {
        private val pathFlags = listOf("--java-home", "--konan-home", "--konan-data", "--cache-dir", "--artifact-cache", "--source-lock", "--source-cache", "--runtime-store")
        private val policyFlags = listOf("--offline", "--no-build")

        fun parse(bytes: ByteArray): BootstrapLaunch {
            require(bytes.size <= 32 * 1024) { "Bootstrap launch context exceeds 32 KiB" }
            val fields = parseJson(bytes.decodeToString(throwOnInvalidSequence = true)).fields(
                setOf("schemaVersion", "sourceRoot", "python", "launcher", "bootstrapArguments", "composition", "runtime"))
            fields.requireVersion()
            val root = absolute(fields.getValue("sourceRoot").stringValue())
            val python = absolute(fields.getValue("python").stringValue())
            val launcher = absolute(fields.getValue("launcher").stringValue())
            require(launcher == child(root, "kvik")) { "Bootstrap launcher must belong to its source root" }
            val arguments = fields.getValue("bootstrapArguments").arrayValue().map { it.stringValue() }
            require(arguments.size in 6..18) { "Invalid bootstrap argument count" }
            val paths = mutableMapOf<String, String>()
            var cursor = 0
            var previousFlag = -1
            while (cursor < arguments.size) {
                val flag = arguments[cursor++]
                val position = (pathFlags + policyFlags).indexOf(flag)
                require(position > previousFlag) { "Unknown, repeated or out-of-order bootstrap flag: $flag" }
                previousFlag = position
                if (flag in pathFlags) {
                    require(cursor < arguments.size) { "Missing bootstrap path for $flag" }
                    paths[flag] = absolute(arguments[cursor++])
                }
            }
            require(paths.keys.containsAll(listOf("--konan-data", "--cache-dir", "--artifact-cache"))) {
                "Bootstrap context must capture data and cache paths"
            }
            require("--source-cache" !in paths || "--source-lock" in paths) {
                "Bootstrap source cache requires a source lock"
            }
            val composition = Digest.fromJson(fields.getValue("composition"))
            val runtime = absolute(fields.getValue("runtime").stringValue())
            require(runtime == child(paths.getValue("--cache-dir"), "executables/${composition.hash}/kvik.kexe")) {
                "Bootstrap runtime path does not match its cache and composition"
            }
            return BootstrapLaunch(root, python, launcher, arguments, composition, runtime)
        }

        fun parseComposition(bytes: ByteArray): Digest {
            require(bytes.size <= 1024) { "Bootstrap composition response exceeds 1 KiB" }
            val fields = parseJson(bytes.decodeToString(throwOnInvalidSequence = true))
                .fields(setOf("schemaVersion", "composition"))
            fields.requireVersion()
            return Digest.fromJson(fields.getValue("composition"))
        }

        private fun absolute(value: String): String {
            require(value.startsWith('/') && '\u0000' !in value) { "Bootstrap paths must be absolute and contain no NUL" }
            // Preserve lexical path spelling, including .. and symlinks: normalization can change resolution.
            return value
        }

        private fun child(root: String, suffix: String) = "${root.trimEnd('/')}/$suffix"
    }
}
