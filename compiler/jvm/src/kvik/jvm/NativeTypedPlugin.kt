@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)
package kvik.jvm

import org.jetbrains.kotlin.cli.plugins.processCompilerPluginOptions
import org.jetbrains.kotlin.compiler.plugin.CliOptionValue
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration

/** Loaded only inside the owned Native compiler loader, never the JVM worker bootstrap loader. */
class NativeTypedPlugin : CompilerPluginRegistrar() {
    private data class Binding(val registrar: CompilerPluginRegistrar, val processor: CommandLineProcessor?, val options: List<CliOptionValue>)
    companion object {
        private var binding: Binding? = null
        @JvmStatic fun bind(registrar: CompilerPluginRegistrar, processor: CommandLineProcessor?, options: List<CliOptionValue>) {
            check(binding == null) { "Native plugin bridge is already bound" }
            binding = Binding(registrar, processor, options.toList())
        }
        @JvmStatic fun clear() { binding = null }
        private fun current(): Binding = checkNotNull(binding) { "Native plugin bridge is not bound" }
    }
    override val pluginId: String get() = current().registrar.pluginId
    override val supportsK2: Boolean get() = current().registrar.supportsK2
    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val current = current()
        current.processor?.let { processCompilerPluginOptions(it, current.options, configuration) }
        val storage = this
        with(current.registrar) { storage.registerExtensions(configuration) }
    }
}
