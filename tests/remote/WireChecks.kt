package kvik.cache.remote.tests

import kvik.cache.remote.*
import kvik.core.*

internal fun wireChecks(action: ActionSpec, limits: TransferLimits) {
    fun fails(block: () -> Unit) {
        var rejected = false
        try { block() } catch (_: Exception) { rejected = true }
        check(rejected) { "Malformed fixture was accepted" }
    }
    val mapping = ReapiMapping(action, limits)
    check(mapping.actionDigest != mapping.localKey)
    val actionWire = ProtoMessage(mapping.blobs.getValue(mapping.actionDigest))
    val command = ProtoMessage(mapping.blobs.getValue(actionWire.digest(1)))
    check(command.repeated(1).map { it.decodeToString() } == action.arguments)
    check(ReapiMapping(action.copy(arguments = action.arguments.reversed()), limits).actionDigest != mapping.actionDigest)
    check(ReapiMapping(action.copy(parameters = obj("changed" to true.json())), limits).actionDigest != mapping.actionDigest)
    check(ReapiMapping(action.copy(timeoutMillis = 2000), limits).actionDigest != mapping.actionDigest)
    check(ReapiMapping(action.copy(environment = mapOf("CHANGED" to "1")), limits).actionDigest != mapping.actionDigest)
    val digest = Sha256.digest("value".encodeToByteArray())
    fun file(path: String) = proto { text(1, path); bytes(2, digest.wire()) }
    fails { mapping.decodeResult(proto { bytes(2, file("../escape")) }) { error("No fetch") } }
    fails { mapping.decodeResult(proto { bytes(2, file("unrelated")) }) { error("No fetch") } }
    fails { mapping.decodeResult(proto { bytes(2, file("single.txt")); bytes(2, file("single.txt")) }) { error("No fetch") } }
    fails { mapping.decodeResult(proto { bytes(12, proto { text(1, "single.txt"); text(2, "../escape") }) }) { error("No fetch") } }
    fails { mapping.decodeResult(proto { number(4, 1) }) { error("No fetch") } }
    fails { ProtoMessage(byteArrayOf(0x12, 0x05, 0x01)) }
    fails { ProtoMessage(ByteArray(12) { 0xff.toByte() }) }
    val missingTree = proto { bytes(1, proto { bytes(2, proto { text(1, "child"); bytes(2, digest.wire()) }) }) }
    val treeDigest = Sha256.digest(missingTree)
    fails { mapping.decodeResult(proto { bytes(3, proto { text(1, "out"); bytes(3, treeDigest.wire()) }) }) { missingTree } }
    val symlinkTree = proto { bytes(1, proto { bytes(3, proto { text(1, "link"); text(2, "../escape") }) }) }
    fails { mapping.decodeResult(proto { bytes(3, proto { text(1, "out"); bytes(3, Sha256.digest(symlinkTree).wire()) }) }) { symlinkTree } }
    val collisionTree = proto { bytes(1, proto { bytes(1, file("same")); bytes(2, proto { text(1, "same"); bytes(2, digest.wire()) }) }) }
    fails { mapping.decodeResult(proto { bytes(3, proto { text(1, "out"); bytes(3, Sha256.digest(collisionTree).wire()) }) }) { collisionTree } }
    val inline = proto { bytes(2, proto { text(1, "single.txt"); bytes(2, digest.wire()); bytes(5, byteArrayOf(1)) }) }
    fails { mapping.decodeResult(inline) { error("No fetch") } }
    val tiny = ReapiMapping(action, limits.copy(files = 2))
    fails { tiny.decodeResult(proto { bytes(2, file("single.txt")); bytes(2, file("single.txt")); bytes(2, file("single.txt")) }) { error("No fetch") } }
    println("PASS native wire mapping, identity sensitivity, empty arguments, malformed/traversal/symlink/duplicate/tree/inline rejection")
}
