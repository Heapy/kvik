package kvik.core

enum class ArtifactShape(val wireName: String) { FILE("file"), TREE("tree"), FILE_OR_TREE("file-or-tree") }

/** A path inside an action's output manifest. A tree may contain zero files. */
data class OutputArtifact(val path: LogicalPath, val kind: String, val shape: ArtifactShape) {
    init { require(kind.matches(Regex("[a-z][a-z0-9-]{0,63}"))) { "Invalid artifact kind" } }
    fun toJson(): JsonValue.Object = obj("path" to path.value.json(), "kind" to kind.json(), "shape" to shape.wireName.json())
    fun contains(file: LogicalPath): Boolean = when (shape) {
        ArtifactShape.FILE -> file == path
        ArtifactShape.TREE -> file.value.startsWith(path.value + "/")
        ArtifactShape.FILE_OR_TREE -> file == path || file.value.startsWith(path.value + "/")
    }
}

/** Complete declared roots, never a prediction of the files that an action will actually emit. */
class ActionOutputContract(artifacts: List<OutputArtifact>) {
    val artifacts: List<OutputArtifact> = artifacts.toList()
    constructor(vararg artifacts: OutputArtifact) : this(artifacts.toList())
    init { require(this.artifacts.map { it.path }.distinct().size == this.artifacts.size) { "Duplicate output contract path" } }
    fun requireMatches(action: ActionSpec) {
        require(action.outputs.toSet() == artifacts.map { it.path }.toSet()) { "Resolved action outputs differ from their declared contract" }
    }
    fun requireMatches(outputs: TreeManifest) {
        require(outputs.files.all { file -> artifacts.any { it.contains(file.path) } }) { "Output files differ from their declared artifact shapes" }
    }
    fun toJson(): JsonValue = artifacts.sortedBy { it.path.value }.map { it.toJson() }.json()
}

fun fileOutput(path: String, kind: String) = OutputArtifact(LogicalPath(path), kind, ArtifactShape.FILE)
fun treeOutput(path: String, kind: String) = OutputArtifact(LogicalPath(path), kind, ArtifactShape.TREE)
