package kvik.jvm

import kvik.core.KotlinJsTarget
import java.nio.file.Files

/** Run with the worker sources so the argument boundary is tested without loading a compiler. */
object WorkerArgumentsTest {
    @JvmStatic fun main(args: Array<String>) {
        val root = Files.createTempDirectory("kvik-js-worker-arguments-")
        val workspace = Workspace(root)
        try {
            Files.createDirectories(root.resolve("input"))
            Files.createDirectories(root.resolve("output"))
            listOf("Main.kt", "Common.kt", "stdlib.klib", "other.klib").forEach { Files.writeString(root.resolve("input/$it"), "") }
            val base = listOf("-ir-output-dir", "output/.", "-ir-output-name", "main", "-Xir-module-name=module",
                "-language-version", "2.4", "-api-version", "2.4", "-libraries", "input/stdlib.klib", "input/Main.kt")
            var checks = 0
            fun accept(arguments: List<String>, target: KotlinJsTarget = KotlinJsTarget.JS, libraries: List<String> = listOf("input/stdlib.klib")) {
                validateJsPluginArguments(arguments, target, libraries, workspace); checks++
            }
            fun reject(arguments: List<String>, target: KotlinJsTarget = KotlinJsTarget.JS, libraries: List<String> = listOf("input/stdlib.klib")) {
                check(runCatching { validateJsPluginArguments(arguments, target, libraries, workspace) }.exceptionOrNull() is IllegalArgumentException) {
                    "Unsafe argument set was accepted: $arguments"
                }
                checks++
            }
            accept(base)
            accept(base.map { if (it == "-Xir-module-name=module") "-Xir-module-name=org.example:library" else it })
            reject(base.map { if (it == "-Xir-module-name=module") "-Xir-module-name=../outside" else it })
            accept(listOf("-Xwasm-target=wasm-js") + base, KotlinJsTarget.WASM_JS)
            accept(base + listOf("input/Common.kt", "-Xmulti-platform", "-Xcommon-sources=input/Common.kt", "-opt-in=example.Experimental", "-Werror", "-Xexplicit-api=strict"))
            accept(base.filterNot { it == "-libraries" || it == "input/stdlib.klib" }, libraries = emptyList())
            reject(base, KotlinJsTarget.WASM_JS)
            reject(base + "-Xwasm-target=wasm-js")
            reject(base + "-Xwasm-target=wasm-wasi", KotlinJsTarget.WASM_JS)
            reject(base + "-Xplugin=input/rogue.jar")
            reject(base + listOf("-P", "plugin:rogue:value=foo,bar"))
            reject(base + "-Xir-produce-js")
            reject(base + "-Xinclude=input/stdlib.klib")
            reject(base + "@input/arguments.txt")
            reject(base.map { if (it == "output/.") "../outside" else it })
            reject(base.map { if (it == "output/.") "output/../outside" else it })
            reject(base.map { if (it == "main") "../outside" else it })
            reject(base.map { if (it == "2.4") "2.3" else it })
            reject(base + listOf("-ir-output-dir", "output/second"))
            reject(base + listOf("-libraries", "input/other.klib"))
            reject(base, libraries = listOf("input/other.klib"))
            reject(base, libraries = listOf("input/stdlib.klib", "input/stdlib.klib"))
            reject(base + "input/Main.kt")
            reject(base.filterNot { it == "input/Main.kt" })
            reject(base + "input/Missing.kt")
            reject(base + "input/../Main.kt")
            reject(base + listOf("-Xmulti-platform", "-Xcommon-sources=input/Common.kt"))
            reject(base + "-Xcommon-sources=input/Main.kt")
            reject(base + listOf("-Xmulti-platform", "-Xcommon-sources=input/Main.kt,input/Main.kt"))
            reject(base + listOf("-opt-in=example.Experimental", "-opt-in=example.Experimental"))
            reject(base + "-opt-in=../Unsafe")
            reject(base.filterNot { it.startsWith("-Xir-module-name=") })
            reject(base + "-ir-output-name")
            Files.createSymbolicLink(root.resolve("input/Alias.kt"), root.resolve("input/Main.kt"))
            reject(base + "input/Alias.kt")
            Files.createDirectory(root.resolve("input/Directory.kt"))
            reject(base + "input/Directory.kt")
            println("PASS $checks JS/Wasm worker argument checks")
        } finally { workspace.removeTree(root) }
    }
}
