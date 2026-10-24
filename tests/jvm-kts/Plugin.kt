@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)
package kvik.kts.fixture

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.compiler.plugin.*
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.cli.reportInfo
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid

private val TEXT = CompilerConfigurationKey<String>("replacement text")
class Processor : CommandLineProcessor {
    override val pluginId = "kvik.fixture.kts"
    override val pluginOptions = listOf(CliOption("text", "text", "Replacement", true, false))
    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        require(option.optionName == "text")
        configuration.put(TEXT, value)
    }
}
class Registrar : CompilerPluginRegistrar() {
    override val pluginId = "kvik.fixture.kts"
    override val supportsK2 = true
    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        configuration.reportInfo("fixture plugin informational message")
        val text = requireNotNull(configuration.get(TEXT))
        IrGenerationExtension.registerExtension(object : IrGenerationExtension {
            override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
                moduleFragment.transformChildren(object : IrElementTransformerVoid() {
                    override fun visitConst(expression: IrConst): IrExpression =
                        if (expression.value == "PLUGIN_MARKER") IrConstImpl.string(expression.startOffset, expression.endOffset, expression.type, text)
                        else expression
                }, null)
            }
        })
    }
}
