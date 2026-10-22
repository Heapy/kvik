package kvik.core

const val JVM_TARGET_VERSION_ATTRIBUTE: String = "org.gradle.jvm.version"
const val DEFAULT_JVM_TARGET: Int = 11
const val COMPILER_PLUGIN_JVM_HOST: Int = 25

fun supportedJvmTarget(value: String): Int {
    require(value in setOf("11", "17", "21", "25")) { "Unsupported JVM target: $value" }
    return value.toInt()
}

/** Shared interpretation of the one target setting used by resolution and compilation. */
fun jvmTargetFromOptions(arguments: List<String>): Int {
    var target = DEFAULT_JVM_TARGET
    var found = false
    for ((index, argument) in arguments.withIndex()) {
        if (argument == "-jvm-target" || argument.startsWith("-jvm-target=")) {
            require(!found) { "Repeated compiler option: -jvm-target" }
            found = true
            target = supportedJvmTarget(if ('=' in argument) argument.substringAfter('=')
                else arguments.getOrNull(index + 1) ?: error("Missing value for -jvm-target"))
        }
    }
    return target
}

fun jvmVariantRequest(usage: String, target: Int): VariantRequest {
    supportedJvmTarget(target.toString())
    require(usage in setOf("java-api", "java-runtime")) { "Unsupported JVM usage: $usage" }
    return VariantRequest("jvm", usage, mapOf(JVM_TARGET_VERSION_ATTRIBUTE to target.toString()))
}
