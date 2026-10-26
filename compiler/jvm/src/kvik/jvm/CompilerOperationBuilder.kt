@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class,
    org.jetbrains.kotlin.buildtools.api.arguments.ExperimentalCompilerArgument::class)

package kvik.jvm

import kvik.core.NativeTarget
import java.nio.file.Path
import org.jetbrains.kotlin.buildtools.api.BaseCompilationOperation
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.arguments.CommonCompilerArguments

/** The base builder gained arguments/build in 2.4.20; JVM builders provide them in both versions. */
internal class CompilerOperationBuilder(
    val configuration: BaseCompilationOperation.Builder,
    val compilerArguments: CommonCompilerArguments.Builder,
    val build: () -> BaseCompilationOperation,
)

/** Metadata BTA interfaces exist only in 2.4.20. Keep this branch out of 2.4.10 linkage. */
internal fun metadataCompilationBuilder(toolchains: KotlinToolchains, sources: List<Path>, destination: Path,
    options: List<String>, classpath: List<Path>, targets: Set<String>): CompilerOperationBuilder {
    require(toolchains.getCompilerVersion() == "2.4.20")
    val loader = KotlinToolchains::class.java.classLoader
    fun api(name: String) = Class.forName("org.jetbrains.kotlin.buildtools.api.$name", true, loader)
    val toolchainType = api("metadata.KotlinMetadataPlatformToolchain").asSubclass(KotlinToolchains.Toolchain::class.java)
    val toolchain = toolchains.getToolchain(toolchainType)
    val factory = toolchainType.getMethod("metadataKlibCompilationOperationBuilder", List::class.java, Path::class.java)
    val builder = factory.invoke(toolchain, sources, destination)
    val builderType = factory.returnType
    val arguments = builderType.getMethod("getCompilerArguments").invoke(builder) as CommonCompilerArguments.Builder
    arguments.applyArgumentStrings(options)
    val metadataArguments = api("arguments.MetadataArguments")
    val metadataBuilder = api("arguments.MetadataArguments\$Builder")
    val setter = metadataBuilder.getMethod("set", api("arguments.MetadataArguments\$MetadataArgument"), Any::class.java)
    setter.invoke(arguments, metadataArguments.getField("CLASSPATH").get(null), classpath)
    val targetType = api("arguments.enums.MetadataTargetPlatform")
    val platforms = buildList {
        if ("jvm" in targets) add(targetType.getField("JVM").get(null))
        if ("js" in targets) add(targetType.getField("JS").get(null))
        if ("wasmJs" in targets) add(targetType.getField("WASM_JS").get(null))
        if (targets.any { NativeTarget.find(it) != null }) add(targetType.getField("NATIVE").get(null))
    }
    setter.invoke(arguments, metadataArguments.getField("X_TARGET_PLATFORM").get(null), platforms)
    return CompilerOperationBuilder(builder as BaseCompilationOperation.Builder, arguments) {
        builderType.getMethod("build").invoke(builder) as BaseCompilationOperation
    }
}
