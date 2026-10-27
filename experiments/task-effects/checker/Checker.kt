@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class, org.jetbrains.kotlin.config.MessageCollectorAccess::class)
package kvik.experimental.effects.checker

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

class Registrar : CompilerPluginRegistrar() {
    override val pluginId = "kvik.experimental.task-effects"
    override val supportsK2 = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val messages = configuration.getNotNull(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY)
        IrGenerationExtension.registerExtension(object : IrGenerationExtension {
            override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
                Checker(pluginContext, messages).check(moduleFragment)
            }
        })
    }
}

private data class Permissions(val environment: Set<String>, val standardOutput: Boolean, val clock: Boolean)

/** Closed, deliberately small IR subset. This is a diagnostic experiment, not a sandbox. */
private class Checker(private val context: IrPluginContext, private val messages: MessageCollector) {
    private val annotationName = FqName("kvik.experimental.effects.Task")
    private val scalarTypes = with(context.irBuiltIns) { setOf(intClass, longClass, booleanClass, stringClass, unitClass) }
    private val arithmetic = with(context.irBuiltIns) {
        listOf(intClass, longClass).flatMap { type ->
            type.owner.declarations.filterIsInstance<IrSimpleFunction>().filter {
                it.name.asString() in setOf("plus", "minus", "times", "div", "rem", "unaryMinus", "unaryPlus", "compareTo")
            }.map { it.symbol }
        }.toSet() + eqeqSymbol
    }

    fun check(module: IrModuleFragment) {
        val functions = mutableSetOf<IrSimpleFunction>()
        module.acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                if (element is IrSimpleFunction) functions += element
                element.acceptChildrenVoid(this)
            }
        })
        for (root in functions) {
            val annotation = root.getAnnotation(annotationName) ?: continue
            val arguments = annotation.argumentMapping
            val environment = (arguments[Name.identifier("environment")] as? IrVararg)?.elements.orEmpty()
                .mapNotNull { (it as? IrConst)?.value as? String }.toSet()
            val permissions = Permissions(environment,
                (arguments[Name.identifier("standardOutput")] as? IrConst)?.value == true,
                (arguments[Name.identifier("clock")] as? IrConst)?.value == true)
            for (file in module.files) for (declaration in file.declarations) {
                if (declaration is IrProperty && !declaration.isConst)
                    report("KVIK_TASK_EFFECT_UNSUPPORTED", "Module-level properties require separate initialization analysis",
                        root, root, listOf(root.name.asString()))
            }
            visitFunction(root, permissions, functions, mutableSetOf(), emptyList())
        }
    }

    private fun report(code: String, message: String, function: IrSimpleFunction, site: IrElement, chain: List<String>) {
        val file = function.file.fileEntry
        val offset = site.startOffset.coerceAtLeast(0)
        messages.report(CompilerMessageSeverity.ERROR,
            "[$code] $message; task call path: ${chain.joinToString(" -> ")}",
            CompilerMessageLocation.create(file.name, file.getLineNumber(offset) + 1, file.getColumnNumber(offset) + 1, null))
    }

    private fun visitFunction(
        function: IrSimpleFunction, permissions: Permissions,
        functions: Set<IrSimpleFunction>, visited: MutableSet<IrSimpleFunction>, parents: List<String>,
    ) {
        val chain = parents + (function.fqNameWhenAvailable?.asString() ?: function.name.asString())
        if (!visited.add(function)) return
        if (chain.size > 128 || visited.size > 4096) {
            report("KVIK_TASK_EFFECT_UNSUPPORTED", "Task call graph exceeds the experiment's analysis limit", function, function, chain)
            return
        }
        if (function.parent !is IrFile || function.isSuspend || function.isExternal || function.body == null ||
            function.typeParameters.isNotEmpty() || function.returnType.classOrNull !in scalarTypes ||
            function.parameters.any { it.kind != IrParameterKind.Regular || it.defaultValue != null || it.type.classOrNull !in scalarTypes }) {
            report("KVIK_TASK_EFFECT_UNSUPPORTED", "Only top-level scalar functions without defaults, receivers or suspension are supported",
                function, function, chain)
            return
        }
        function.body!!.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                when (element) {
                    is IrCall -> {
                        val callee = element.symbol.owner
                        val name = callee.fqNameWhenAvailable?.asString()
                        when {
                            callee in functions -> visitFunction(callee, permissions, functions, visited, chain)
                            element.symbol in arithmetic -> Unit
                            name == "kotlin.io.println" || name == "kotlin.io.print" -> {
                                if (!permissions.standardOutput) report("KVIK_TASK_EFFECT_DENIED", "Standard output requires standardOutput=true", function, element, chain)
                            }
                            name == "java.lang.System.getenv" -> {
                                val key = (element.arguments.singleOrNull() as? IrConst)?.value as? String
                                if (key == null || key !in permissions.environment)
                                    report("KVIK_TASK_EFFECT_DENIED", "Environment access requires an exact permitted literal key", function, element, chain)
                            }
                            name == "java.lang.System.nanoTime" || name == "java.lang.System.currentTimeMillis" -> {
                                if (!permissions.clock) report("KVIK_TASK_EFFECT_DENIED", "Clock access requires clock=true", function, element, chain)
                            }
                            else -> report("KVIK_TASK_EFFECT_UNSUPPORTED", "Unanalysed call ${name ?: callee.name}", function, element, chain)
                        }
                        if (element.arguments.any { it == null })
                            report("KVIK_TASK_EFFECT_UNSUPPORTED", "Implicit/default arguments are unsupported", function, element, chain)
                    }
                    is IrBlockBody, is IrExpressionBody, is IrReturn, is IrConst, is IrGetValue,
                    is IrSetValue, is IrBlock, is IrComposite, is IrWhen, is IrBranch,
                    is IrWhileLoop, is IrDoWhileLoop, is IrBreak, is IrContinue -> Unit
                    is IrVariable -> if (element.type.classOrNull !in scalarTypes)
                        report("KVIK_TASK_EFFECT_UNSUPPORTED", "Non-scalar local variable", function, element, chain)
                    is IrTypeOperatorCall -> if (element.operator !in setOf(IrTypeOperator.IMPLICIT_CAST, IrTypeOperator.IMPLICIT_COERCION_TO_UNIT))
                        report("KVIK_TASK_EFFECT_UNSUPPORTED", "Unsupported type operation ${element.operator}", function, element, chain)
                    else -> report("KVIK_TASK_EFFECT_UNSUPPORTED", "Unsupported IR operation ${element.javaClass.simpleName}", function, element, chain)
                }
                element.acceptChildrenVoid(this)
            }
        })
    }
}
