package kvik.resolution.probe

import kvik.core.*
import kvik.cache.*
import kvik.locking.*
import kvik.resolution.*

/** Replays the public CLI's exact configuration/pin arguments through its Native lock pipeline. */
fun main(args: Array<String>) {
    require(args.size == 2)
    val command = parseJson(NativeFiles.read(args[0], 4 * 1024 * 1024).decodeToString()).arrayValue().map { it.stringValue() }
    val output = args[1]
    NativeFiles.ensureDirectory(output)
    NativeFiles.ensureDirectory("$output/metadata")
    fun write(name: String, value: JsonValue) = NativeFiles.publish("$output/$name", value.canonical().encodeToByteArray(), 16 * 1024 * 1024)
    val pins = mutableListOf<Coordinate>()
    val scopedPins = mutableListOf<ScopedPin>()
    var project: String? = null
    var index = 2
    while (index < command.size) {
        val flag = command[index++]; val value = command[index++]
        when (flag) {
            "--project" -> project = value
            "--pin" -> pins += Coordinate.parse(value)
            "--scope-pin" -> {
                val fields = value.substringBefore('=').split(':')
                require(fields.size == 5 && '=' in value)
                scopedPins += ScopedPin(ResolutionDomain(fields[0], fields[1], fields[2], fields[3], fields[4]), Coordinate.parse(value.substringAfter('=')))
            }
            else -> error("Unsupported captured argument: $flag")
        }
    }
    val configuration = parseProjectConfiguration(parseJson(NativeFiles.read(project ?: error("Missing project"), 4 * 1024 * 1024).decodeToString()))
    val effective = effectiveConfiguration(configuration, ResolverPolicy(pins, scopedPins = scopedPins))
    write("effective.json", effective.document)
    val captured = linkedMapOf<String, JsonValue>()
    val transport = object : ArtifactTransport {
        val https = NativeHttpsTransport()
        override fun fetch(url: String): ByteArray {
            println("FETCH $url")
            return https.fetch(url).also { bytes ->
                if (url.endsWith(".module") || url.endsWith(".pom")) {
                    val digest = Sha256.digest(bytes)
                    val file = "${digest.hash}.${url.substringAfterLast('.')}"
                    NativeFiles.publish("$output/metadata/$file", bytes, 1024 * 1024)
                    captured[url] = obj("file" to file.json(), "digest" to digest.toJson())
                }
            }
        }
    }
    try {
        val service = DependencyLocks(NativeContentStore("$output/cas"), NativePomParser())
        val lock = service.resolve(effective, transport)
        check(service.loadLocked(lock.bytes(), effective) == lock)
        write("lock.json", lock.toJson())
        write("result.json", obj("status" to "resolved".json(), "selections" to lock.selections.size.json(), "configuration" to effective.digest.toJson()))
    } catch (failure: Exception) {
        write("result.json", obj("status" to "failed".json(), "message" to (failure.message ?: failure.toString()).json()))
        throw failure
    } finally {
        write("metadata.json", JsonValue.Object(captured))
    }
}
