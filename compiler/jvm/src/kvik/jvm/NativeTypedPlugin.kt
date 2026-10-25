@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)
package kvik.jvm

import org.jetbrains.kotlin.cli.plugins.processCompilerPluginOptions
import org.jetbrains.kotlin.compiler.plugin.CliOptionValue
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration

/** Loaded only inside an owned compiler loader, never the JVM worker bootstrap loader. */
class NativeTypedPlugin : CompilerPluginRegistrar() {
    private data class Binding(val registrar: CompilerPluginRegistrar, val processor: CommandLineProcessor?, val options: List<CliOptionValue>)
    companion object {
        private var bindings: List<Binding>? = null
        @JvmStatic fun bind(registrar: CompilerPluginRegistrar, processor: CommandLineProcessor?, options: List<CliOptionValue>) {
            bindAll(listOf(registrar), listOf(processor), listOf(options))
        }
        @JvmStatic fun bindAll(registrars: List<CompilerPluginRegistrar>, processors: List<CommandLineProcessor?>,
                              options: List<List<CliOptionValue>>) {
            check(bindings == null) { "Native plugin bridge is already bound" }
            require(registrars.size in 1..16 && processors.size == registrars.size && options.size == registrars.size)
            require(registrars.map { it.pluginId }.distinct().size == registrars.size) { "Duplicate compiler plugin ID" }
            bindings = registrars.indices.map { Binding(registrars[it], processors[it], options[it].toList()) }
        }
        @JvmStatic fun clear() { bindings = null }
        private fun current(): List<Binding> = checkNotNull(bindings) { "Native plugin bridge is not bound" }
    }
    override val pluginId: String get() = current().singleOrNull()?.registrar?.pluginId ?: "kvik.workspace.composition"
    override val supportsK2: Boolean get() = current().all { it.registrar.supportsK2 }
    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val storage = this
        for (binding in current()) {
            binding.processor?.let { processCompilerPluginOptions(it, binding.options, configuration) }
            with(binding.registrar) { storage.registerExtensions(configuration) }
        }
    }
}
