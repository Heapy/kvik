package kvik.bsp

import kvik.core.*

/** A source plugin's declared output root, reconstructed from the completed build's inputs. */
data class BspPluginGeneratedRoot(val producer: String, val module: String, val sourceSet: String,
                                  val outputRoot: LogicalPath, val resources: List<LogicalPath>? = null) {
    init {
        require(producer.startsWith("plugin:") && ":generate:" in producer && producer.length <= 256)
        require(module.isNotBlank() && sourceSet.isNotBlank())
        require(resources == null || resources.isNotEmpty() && resources.distinct().size == resources.size)
    }
    val prefix: String get() = "generated/plugins/${Sha256.digest(producer.encodeToByteArray()).hash}/${outputRoot.value}/"
}
