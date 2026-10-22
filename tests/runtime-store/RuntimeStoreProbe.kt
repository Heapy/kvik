package kvik.runtimestore.tests

import kvik.cache.NativeActionCache
import kvik.cache.NativeContentStore
import kvik.cache.NativeOutputTrees
import kvik.core.CachedAction
import kvik.core.Digest
import kvik.core.canonical
import kvik.core.json
import kvik.core.obj
import kvik.core.parseJson

/** Protocol probe: all store operations go through unchanged production K04. */
fun main(args: Array<String>) {
    require(args.size in 3..4) { "operation store digest-json [tree]" }
    val store = NativeContentStore(args[1])
    val cache = NativeActionCache("${args[1]}/runtime-actions", store)
    val trees = NativeOutputTrees(store)
    val key = Digest.fromJson(parseJson(args[2]))
    val action = when (args[0]) {
        "publish" -> {
            require(args.size == 4)
            CachedAction(key, trees.capture(args[3])).also(cache::publish)
        }
        "lookup", "restore" -> cache.lookup(key)
        else -> error("Unsupported probe operation")
    }
    if (action == null) {
        println("null")
        return
    }
    if (args[0] == "restore") {
        require(args.size == 4)
        trees.materialize(action.outputs, args[3])
    }
    println(obj("schemaVersion" to 1.json(), "key" to action.key.toJson(), "outputs" to action.outputs.toJson()).canonical())
}
