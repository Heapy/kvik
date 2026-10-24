package kvik.cli

import kvik.bsp.BspProjectModel
import kvik.bsp.BspTarget
import kvik.cache.NativeFiles
import kvik.core.*
import kvik.locking.*
import kvik.resolution.NativePomParser

/**
 * BSP has no usage selector: report the union of this compilation's compile and runtime libraries.
 * Effective configuration applies dependencyClosure, including main dependencies for test targets;
 * loadLocked proves selected versions and transitive artifacts against immutable cached metadata.
 * Host tools have separate purposes and never become application dependency modules.
 */
internal fun bspDependencyModules(model: BspProjectModel, options: ProjectOptions, targets: List<BspTarget>): JsonValue.Object {
    val project = readProject(options.project)
    val effective = effectiveConfiguration(project.configuration, options.resolverPolicy())
    require(project.root == model.root && project.name == model.configurationPath &&
        effective.digest == model.inspection.configuration) { "BSP project model is stale; reload the workspace" }
    val lockPath = projectPath(project.root, options.lock)
    NativeFiles.ensureDirectory(lockPath.substringBeforeLast('/'), create = false)
    val lockBytes = NativeFiles.read(lockPath, 16 * 1024 * 1024)
    // A project with no external libraries can verify an empty lock without creating a CAS directory.
    val store by lazy { ReadOnlyContentStore(projectPath(project.root, options.store), effective.maxArtifactBytes) }
    val content = object : ContentStore {
        override fun put(bytes: ByteArray): Digest = error("BSP dependency queries cannot publish content")
        override fun get(digest: Digest): ByteArray? = store.get(digest)
    }
    val lock = DependencyLocks(content, NativePomParser()).loadLocked(lockBytes, effective)
    val result = obj("items" to targets.map { target ->
        val coordinates = lock.selections.asSequence().filter { selection ->
            val domain = selection.domain
            domain.consumer == ResolutionConsumer.Module(target.module) && domain.target == target.target &&
                domain.compilation == target.compilation && domain.purpose == "dependencies"
        }.flatMap { it.artifacts.asSequence() }.map { it.coordinate }.distinct().sortedBy(Coordinate::toString)
        obj("target" to obj("uri" to target.uri.json()), "modules" to coordinates.map { coordinate ->
            obj("name" to "${coordinate.group}:${coordinate.name}".json(), "version" to coordinate.version.json(),
                "dataKind" to "maven".json(), "data" to obj("organization" to coordinate.group.json(),
                    "name" to coordinate.name.json(), "version" to coordinate.version.json(),
                    // CAS paths are immutable blobs, not public named JARs. Import must not materialize
                    // files or invent source classifiers; a stable materialization contract can add URIs.
                    "artifacts" to emptyList<JsonValue>().json()))
        }.toList().json())
    }.json())
    require(NativeFiles.read(project.path, 4 * 1024 * 1024).contentEquals(project.bytes) &&
        NativeFiles.read(lockPath, 16 * 1024 * 1024).contentEquals(lockBytes)) {
        "Project configuration or dependency lock changed during BSP query; retry"
    }
    return result
}
