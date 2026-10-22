package kvik.core

const val JVM_TARGET_VERSION_ATTRIBUTE: String = "org.gradle.jvm.version"
const val DEFAULT_JVM_TARGET: Int = 11
const val COMPILER_PLUGIN_JVM_HOST: Int = 25

fun supportedJvmTarget(value: String): Int {
    require(value in setOf("11", "17", "21", "25")) { "Unsupported JVM target: $value" }
    return value.toInt()
}

data class JvmTargetSettings(val target: Int, val jdkRelease: Int?)

/** Release restricts JDK APIs and sets bytecode target; an explicit target must agree. */
fun jvmTargetSettings(arguments: List<String>): JvmTargetSettings {
    var target: Int? = null
    var release: Int? = null
    for ((index, argument) in arguments.withIndex()) {
        val name = argument.substringBefore('=')
        if (name in setOf("-jvm-target", "-Xjdk-release")) {
            val value = supportedJvmTarget(if ('=' in argument) argument.substringAfter('=')
                else arguments.getOrNull(index + 1) ?: error("Missing value for $name"))
            if (name == "-jvm-target") {
                require(target == null) { "Repeated compiler option: $name" }
                target = value
            } else {
                require(release == null) { "Repeated compiler option: $name" }
                release = value
            }
        }
    }
    require(target == null || release == null || target == release) { "Conflicting -jvm-target and -Xjdk-release" }
    return JvmTargetSettings(release ?: target ?: DEFAULT_JVM_TARGET, release)
}

fun jvmTargetFromOptions(arguments: List<String>): Int = jvmTargetSettings(arguments).target

fun jvmVariantRequest(usage: String, target: Int): VariantRequest {
    supportedJvmTarget(target.toString())
    require(usage in setOf("java-api", "java-runtime")) { "Unsupported JVM usage: $usage" }
    return VariantRequest("jvm", usage, mapOf(JVM_TARGET_VERSION_ATTRIBUTE to target.toString()))
}
