@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)
package fixture.plugin

import fixture.helper.prefix
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.compiler.plugin.*
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid

private val MESSAGE = CompilerConfigurationKey<String>("message file")
private val REPORT = CompilerConfigurationKey<String>("report directory")
private val VALUES = CompilerConfigurationKey<List<String>>("ordered values")

class Processor : CommandLineProcessor {
    override val pluginId = "fixture.rewrite"
    override val pluginOptions = listOf(
        CliOption("messageFile", "path", "Declared message input", true, false),
        CliOption("reportDirectory", "path", "Declared output directory", true, false),
        CliOption("value", "text", "Ordered literal values", false, true),
    )
    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option.optionName) {
            "messageFile" -> configuration.put(MESSAGE, value)
            "reportDirectory" -> configuration.put(REPORT, value)
            "value" -> configuration.put(VALUES, configuration.get(VALUES).orEmpty() + value)
            else -> error("Unexpected option")
        }
    }
}

class Registrar : CompilerPluginRegistrar() {
    override val pluginId = "fixture.rewrite"
    override val supportsK2 = true
    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        // This implementation is intentionally reachable only at plugin runtime, not its compile CP.
        val runtime = Class.forName("fixture.runtime.RuntimeValue").getMethod("value").invoke(null) as String
        val message = Files.readString(Path.of(configuration.get(MESSAGE)!!)).trim()
        val report = Path.of(configuration.get(REPORT)!!)
        val transformed = prefix() + "v1:" + message + ":" + runtime
        Files.writeString(report.resolve("result.txt"), transformed + "\n" + configuration.get(VALUES).orEmpty().joinToString("|"))
        if ("escape" in configuration.get(VALUES).orEmpty()) Files.writeString(report.parent.resolve("undeclared.txt"), "undeclared")
        IrGenerationExtension.registerExtension(object : IrGenerationExtension {
            override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
                moduleFragment.transformChildren(object : IrElementTransformerVoid() {
                    override fun visitConst(expression: IrConst): IrExpression =
                        if (expression.value == "PLUGIN_MARKER") IrConstImpl.string(expression.startOffset, expression.endOffset, expression.type, transformed)
                        else expression
                }, null)
            }
        })
    }
}
