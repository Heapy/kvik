package kvik.projectgraph

import kvik.core.*

/** Compiler plugins declare namespace roots; each root may be a file or a directory tree. */
internal fun compilerPluginOutputArtifacts(plugins: List<CompilerPluginSpec>): List<OutputArtifact> =
    plugins.flatMap { plugin -> plugin.outputs.map { path ->
        OutputArtifact(LogicalPath("plugins/${plugin.id}/${path.value}"), "compiler-plugin-output", ArtifactShape.FILE_OR_TREE)
    } }
