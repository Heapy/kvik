package kvik.spike

/** K01 feasibility contract, not the public K02 build-plugin API. */
interface SpikePlugin {
    val id: String
    fun probe(): Map<String, String>
}
