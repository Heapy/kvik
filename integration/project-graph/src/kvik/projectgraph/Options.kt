package kvik.projectgraph

import kvik.core.*
import kvik.compiler.native.NativeLanguageOptions

internal data class CompilerOptions(
    val jvmTarget: Int,
    val nativeModuleName: String,
    val optIns: List<String>,
    val explicitApi: Boolean,
    val warningsAsErrors: Boolean,
    val multiplatform: Boolean,
    val jdkRelease: Int?,
    val nativeLanguageOptions: NativeLanguageOptions,
)

/** Parse only settings represented by the typed compiler adapters. No arbitrary flag forwarding. */
internal fun compilerOptions(module: String, target: String, arguments: List<String>): CompilerOptions {
    val jvm = if (target == "jvm") jvmTargetSettings(arguments) else JvmTargetSettings(DEFAULT_JVM_TARGET, null)
    val jvmTarget = jvm.target
    var nativeModuleName = module
    var explicitApi = false
    var warningsAsErrors = false
    var multiplatform = false
    var returnValueChecker: String? = null
    var nameBasedDestructuring: String? = null
    val optIns = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    var position = 0
    while (position < arguments.size) {
        val argument = arguments[position++]
        val name = argument.substringBefore('=')
        require(name == "-opt-in" || seen.add(name)) { "Repeated compiler option: $name" }
        fun value(): String = if ('=' in argument) argument.substringAfter('=') else arguments.getOrNull(position++) ?: error("Missing value for $name")
        fun flag() { require('=' !in argument) { "Unexpected flag value: $argument" } }
        when (name) {
            "-jvm-target" -> {
                require(target == "jvm") { "JVM option on $target" }
                require(supportedJvmTarget(value()) == jvmTarget)
            }
            "-Xjdk-release" -> {
                require(target == "jvm") { "JVM option on $target" }
                require(supportedJvmTarget(value()) == jvm.jdkRelease)
            }
            "-module-name" -> {
                require(NativeTarget.find(target) != null) { "Custom JVM module names are not represented by the adapter" }
                nativeModuleName = value()
                require(nativeModuleName.matches(Regex("[A-Za-z0-9_.:-]+"))) { "Invalid Native module name" }
            }
            "-language-version", "-api-version" -> {
                require(target == "jvm" && value() == "2.4") { "Only JVM language/API version 2.4 is represented" }
            }
            "-opt-in" -> {
                val annotation = value()
                require(annotation.matches(Regex("[A-Za-z_][A-Za-z0-9_.]*")) && annotation !in optIns) { "Invalid/duplicate opt-in" }
                optIns += annotation
            }
            "-Xexplicit-api" -> { require(value() == "strict"); explicitApi = true }
            "-Werror" -> { flag(); warningsAsErrors = true }
            "-Xmulti-platform" -> { flag(); multiplatform = true }
            "-Xreturn-value-checker" -> {
                require(NativeTarget.find(target) != null) { "Return-value checker settings currently require a Native target" }
                returnValueChecker = value()
            }
            "-Xname-based-destructuring" -> {
                require(NativeTarget.find(target) != null) { "Destructuring settings currently require a Native target" }
                nameBasedDestructuring = value()
            }
            else -> error("Unsupported project compiler option: $argument")
        }
    }
    return CompilerOptions(jvmTarget, nativeModuleName, optIns.toList(), explicitApi, warningsAsErrors, multiplatform, jvm.jdkRelease,
        NativeLanguageOptions(returnValueChecker, nameBasedDestructuring))
}
