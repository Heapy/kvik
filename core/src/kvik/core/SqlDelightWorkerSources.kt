package kvik.core

/** Sources compiled with the locked SQLDelight compiler closure, never added to the bootstrap. */
fun sqlDelightWorkerSources(): Map<LogicalPath, String> = linkedMapOf(
    LogicalPath("tools/sqldelight/SqlDelightEnvironment.kt") to $$"""
/*
 * Copyright (C) 2017 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.cash.sqldelight.core

// Adapted for Kvik: isolated bounded output writers and structured diagnostics.
// Vendored from SQLDelight 2.3.2's Gradle plugin. The optimistic-lock annotator is omitted because
// it is not available in the Gradle-free core artifact used here.

import app.cash.sqldelight.core.compiler.SqlDelightCompiler
import app.cash.sqldelight.core.lang.DatabaseFileType
import app.cash.sqldelight.core.lang.DatabaseFileViewProviderFactory
import app.cash.sqldelight.core.lang.MigrationFile
import app.cash.sqldelight.core.lang.MigrationFileType
import app.cash.sqldelight.core.lang.MigrationParserDefinition
import app.cash.sqldelight.core.lang.SqlDelightFile
import app.cash.sqldelight.core.lang.SqlDelightFileType
import app.cash.sqldelight.core.lang.SqlDelightParserDefinition
import app.cash.sqldelight.core.lang.SqlDelightQueriesFile
import app.cash.sqldelight.core.psi.SqlDelightImportStmt
import app.cash.sqldelight.dialect.api.SqlDelightDialect
import com.alecstrong.sql.psi.core.AnnotationException
import com.alecstrong.sql.psi.core.SqlCoreEnvironment
import com.alecstrong.sql.psi.core.SqlFileBase
import com.alecstrong.sql.psi.core.psi.SqlCreateTableStmt
import com.alecstrong.sql.psi.core.psi.SqlStmt
import com.intellij.core.CoreApplicationEnvironment
import com.intellij.mock.MockModule
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ModuleExtension
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileTypeFileViewProviders
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiManager
import java.io.File
import java.util.StringTokenizer
import kotlin.math.log10
import kotlin.reflect.KClass
import kotlin.system.measureTimeMillis

class SqlDelightEnvironment(
  private val properties: SqlDelightDatabaseProperties,
  private val compilationUnit: SqlDelightCompilationUnit,
  private val verifyMigrations: Boolean,
  override var dialect: SqlDelightDialect,
  moduleName: String,
  private val sourceFolders: List<File> = compilationUnit.sourceFolders
    .filter { it.folder.exists() && !it.dependency }
    .map { it.folder },
  private val dependencyFolders: List<File> = compilationUnit.sourceFolders
    .filter { it.folder.exists() && it.dependency }
    .map { it.folder },
) : SqlCoreEnvironment(sourceFolders.map { it.toPath() }, dependencyFolders.map { it.toPath() }),
  SqlDelightProjectService {
  val project = projectEnvironment.project
  val module = MockModule(project, projectEnvironment.parentDisposable)
  private val moduleName = SqlDelightFileIndex.sanitizeDirectoryName(moduleName)

  init {
    project.registerService(SqlDelightProjectService::class.java, this)

    @Suppress("UnresolvedPluginConfigReference")
    CoreApplicationEnvironment.registerExtensionPoint(
      module.extensionArea,
      ExtensionPointName.create("com.intellij.moduleExtension"),
      ModuleExtension::class.java,
    )

    initializeApplication {
      registerFileType(MigrationFileType, MigrationFileType.defaultExtension)
      registerParserDefinition(MigrationParserDefinition())
      registerFileType(SqlDelightFileType, SqlDelightFileType.defaultExtension)
      registerParserDefinition(SqlDelightParserDefinition())
      registerFileType(DatabaseFileType, DatabaseFileType.defaultExtension)
      FileTypeFileViewProviders.INSTANCE.addExplicitExtension(DatabaseFileType, DatabaseFileViewProviderFactory())
    }
  }

  override var treatNullAsUnknownForEquality: Boolean = properties.treatNullAsUnknownForEquality

  override var generateAsync: Boolean = properties.generateAsync

  override var expandSelectStar: Boolean = properties.expandSelectStar

  override fun module(vFile: VirtualFile) = module

  override fun fileIndex(module: Module): SqlDelightFileIndex = FileIndex()

  override fun resetIndex() = throw UnsupportedOperationException()

  override fun clearIndex() = throw UnsupportedOperationException()

  @JvmName("forSqlFileBases")
  fun forSourceFiles(action: (SqlFileBase) -> Unit) {
    forSourceFiles<SqlFileBase>(action)
  }

  override fun <T : PsiFile> forSourceFiles(
    klass: KClass<T>,
    action: (T) -> Unit,
  ) {
    super.forSourceFiles(klass) {
      if (it.fileType != MigrationFileType ||
        verifyMigrations ||
        properties.deriveSchemaFromMigrations
      ) {
        action(it)
      }
    }
  }

  fun generateSqlDelightFiles(logger: (String) -> Unit): CompilationStatus {
    val errors = sortedMapOf<Long, MutableList<SqlDelightProblem>>()
    annotate(
      emptyList(),
    ) { element, message ->
      val key = (element.containingFile as SqlDelightFile).order ?: Long.MAX_VALUE
      errors.putIfAbsent(key, ArrayList())
      errors[key]!!.add(errorMessage(element, message))
    }
    if (errors.isNotEmpty()) return CompilationStatus.Failure(errors.values.flatten())

    val outputRoot = compilationUnit.outputDirectoryFile.toPath().toAbsolutePath().normalize()
    var writtenBytes = 0L
    val writtenPaths = mutableSetOf<java.nio.file.Path>()
    val writer = writer@{ fileName: String ->
      val path = java.nio.file.Path.of(fileName).toAbsolutePath().normalize()
      require(path.startsWith(outputRoot) && path != outputRoot && path.toString().endsWith(".kt")) { "SQLDelight output escaped its Kotlin source root" }
      require(writtenPaths.add(path) && writtenPaths.size <= 10000) { "Duplicate or excessive SQLDelight output" }
      object : java.io.StringWriter() {
        override fun write(char: Int) {
          require(buffer.length < 8 * 1024 * 1024) { "Oversized generated Kotlin source" }
          super.write(char)
        }
        override fun write(str: String) {
          require(buffer.length.toLong() + str.length <= 8L * 1024 * 1024) { "Oversized generated Kotlin source" }
          super.write(str)
        }
        override fun write(str: String, off: Int, len: Int) {
          require(buffer.length.toLong() + len <= 8L * 1024 * 1024) { "Oversized generated Kotlin source" }
          super.write(str, off, len)
        }
        override fun write(chars: CharArray, off: Int, len: Int) {
          require(buffer.length.toLong() + len <= 8L * 1024 * 1024) { "Oversized generated Kotlin source" }
          super.write(chars, off, len)
        }
        override fun close() {
          val bytes = toString().toByteArray(Charsets.UTF_8)
          writtenBytes += bytes.size
          require(bytes.size <= 8 * 1024 * 1024 && writtenBytes <= 64L * 1024 * 1024) { "Oversized SQLDelight output" }
          var parent = outputRoot
          for (part in outputRoot.relativize(path.parent)) {
            parent = parent.resolve(part)
            if (!java.nio.file.Files.exists(parent, java.nio.file.LinkOption.NOFOLLOW_LINKS)) java.nio.file.Files.createDirectory(parent)
            require(java.nio.file.Files.isDirectory(parent, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "Invalid SQLDelight output directory" }
          }
          java.nio.file.Files.write(path, bytes, java.nio.file.StandardOpenOption.CREATE_NEW)
        }
      }
    }

    var sourceFile: SqlDelightFile? = null
    var topMigrationFile: MigrationFile? = null
    try {
      forSourceFiles {
        if (it is MigrationFile && properties.deriveSchemaFromMigrations) {
          if (topMigrationFile == null || it.order > topMigrationFile!!.order) topMigrationFile = it
          if (sourceFile == null) sourceFile = it
        }

        if (it !is SqlDelightQueriesFile) return@forSourceFiles
        logger("----- START ${it.name} ms -------")
        val timeTaken = measureTimeMillis {
          SqlDelightCompiler.writeInterfaces(module, dialect, it, writer)
          sourceFile = it
        }
        logger("----- END ${it.name} in $timeTaken ms ------")
      }
    } catch (e: AnnotationException) {
      return CompilationStatus.Failure(listOf(errorMessage(e.element!!, e.message!!)))
    }

    topMigrationFile?.let { migrationFile ->
      logger("----- START ${migrationFile.name} ms -------")
      val timeTaken = try {
        measureTimeMillis {
          SqlDelightCompiler.writeInterfaces(
            file = migrationFile,
            output = writer,
            includeAll = true,
          )
          SqlDelightCompiler.writeImplementations(module, migrationFile, moduleName, writer)
        }
      } catch (e: AnnotationException) {
        return CompilationStatus.Failure(listOf(errorMessage(e.element!!, e.message!!)))
      }
      logger("----- END ${migrationFile.name} in $timeTaken ms ------")
    }

    sourceFile?.let {
      try {
        SqlDelightCompiler.writeDatabaseInterface(module, it, moduleName, writer)
        if (it is SqlDelightQueriesFile) {
          SqlDelightCompiler.writeImplementations(module, it, moduleName, writer)
        }
      } catch (e: AnnotationException) {
        return CompilationStatus.Failure(listOf(errorMessage(e.element!!, e.message!!)))
      }
    }

    return CompilationStatus.Success
  }

  private fun errorMessage(element: PsiElement, message: String): SqlDelightProblem =
    SqlDelightProblem(element.containingFile.virtualFile.path, element.lineStart, element.charPositionInLine + 1, message)

  private val PsiElement.charPositionInLine: Int
    get() {
      val file = PsiDocumentManager.getInstance(project).getDocument(containingFile)!!
      return textOffset - file.getLineStartOffset(file.getLineNumber(textOffset))
    }

  private val PsiElement.lineStart: Int
    get() {
      val file = PsiDocumentManager.getInstance(project).getDocument(containingFile)!!
      return file.getLineNumber(textOffset) + 1
    }

  private val PsiElement.lineEnd: Int
    get() {
      val file = PsiDocumentManager.getInstance(project).getDocument(containingFile)!!
      return file.getLineNumber(textOffset + textLength) + 1
    }

  private fun context(element: PsiElement?): PsiElement? = when (element) {
    null -> element
    is SqlCreateTableStmt -> element
    is SqlStmt -> element
    is SqlDelightImportStmt -> element
    else -> context(element.parent)
  }

  sealed class CompilationStatus {
    object Success : CompilationStatus()
    class Failure(val errors: List<SqlDelightProblem>) : CompilationStatus()
  }

  private inner class FileIndex : SqlDelightFileIndex {
    override val contentRoot
      get() = throw UnsupportedOperationException("Content root only usable from IDE")

    override val packageName = properties.packageName
    override val className = properties.className
    override val dependencies = properties.dependencies
    override val isConfigured = true
    override val deriveSchemaFromMigrations = properties.deriveSchemaFromMigrations

    override fun outputDirectory(file: SqlDelightFile) = outputDirectories()
    override fun outputDirectories(): List<String> {
      return listOf(compilationUnit.outputDirectoryFile.absolutePath)
    }

    private val virtualDirectoriesWithDependencies: List<VirtualFile> by lazy {
      return@lazy (sourceFolders + dependencyFolders)
        .map { localFileSystem.findFileByPath(it.absolutePath)!! }
    }

    private val directoriesWithDependencies: List<PsiDirectory> by lazy {
      val psiManager = PsiManager.getInstance(projectEnvironment.project)
      return@lazy virtualDirectoriesWithDependencies.map { psiManager.findDirectory(it)!! }
    }

    private val virtualDirectories: List<VirtualFile> by lazy {
      return@lazy sourceFolders
        .map { localFileSystem.findFileByPath(it.absolutePath)!! }
    }

    private val directories: List<PsiDirectory> by lazy {
      val psiManager = PsiManager.getInstance(projectEnvironment.project)
      return@lazy virtualDirectories.map { psiManager.findDirectory(it)!! }
    }

    override fun packageName(file: SqlDelightFile): String {
      fun PsiFileSystemItem.relativePathUnder(ancestor: PsiDirectory): List<String>? {
        if (this.virtualFile.path == ancestor.virtualFile.path) return emptyList()
        parent?.let {
          return it.relativePathUnder(ancestor)?.plus(name)
        }
        return null
      }

      for (sourceFolder in sourceFolders(file)) {
        val path = file.parent!!.relativePathUnder(sourceFolder)
        if (path != null) {
          return path.joinToString(separator = ".") {
            SqlDelightFileIndex.sanitizeDirectoryName(
              it,
            )
          }
        }
      }

      throw IllegalStateException(
        "Tried to find package name of file ${file.virtualFile!!.path} when" +
          " it is not under any of the source folders $sourceFolders",
      )
    }

    override fun sourceFolders(file: VirtualFile, includeDependencies: Boolean) = if (includeDependencies) virtualDirectoriesWithDependencies else virtualDirectories

    override fun sourceFolders(file: SqlDelightFile, includeDependencies: Boolean) = if (includeDependencies) directoriesWithDependencies else directories
  }
}

data class SqlDelightProblem(val path: String, val line: Int, val column: Int, val message: String)
""".trimStart(),
    LogicalPath("tools/sqldelight/Generate.kt") to $$"""
package kvik.sqldelight

import app.cash.sqldelight.core.*
import app.cash.sqldelight.dialect.api.SqlDelightDialect
import java.io.File
import java.nio.file.*
import kotlin.system.exitProcess

/** Fresh, single-request SQLDelight compiler process; no build-system or consumer classes required. */
object Generate {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 9) { "Expected input root, SQL directory, output root, package, class, module, source set, request id, result file" }
        val input = args[0]; val source = args[1]; val output = args[2]
        val packageName = args[3]; val className = args[4]; val module = args[5]
        val sourceSet = args[6]; val requestId = args[7]; val resultFile = args[8]
        val inputRoot = Path.of(input).toAbsolutePath().normalize()
        val sourceRoot = Path.of(source).toAbsolutePath().normalize()
        val outputRoot = Path.of(output).toAbsolutePath().normalize()
        var status = "success"
        var code = 0
        var diagnostics = emptySequence<String>()
        val result = try {
            require(sourceRoot.startsWith(inputRoot) && sourceRoot != inputRoot)
            require(Files.isDirectory(outputRoot, LinkOption.NOFOLLOW_LINKS))
            val sources = if (Files.exists(sourceRoot, LinkOption.NOFOLLOW_LINKS)) Files.walk(sourceRoot).use { paths ->
                paths.filter { !Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.toList()
            } else emptyList()
            require(sources.all { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && it.toString().endsWith(".sq") }) { "Only regular .sq files are supported" }
            if (sources.isNotEmpty()) {
                val unit = CompilationUnit(sourceSet, setOf(SourceFolder(sourceRoot.toFile(), false)), outputRoot.toFile())
                val properties = DatabaseProperties(packageName, listOf(unit), className, emptyList(), false, false, sourceRoot.toFile(), false, true)
                // The compiler closure includes older dialect implementations transitively; select the pinned dialect explicitly.
                val dialect = app.cash.sqldelight.dialects.sqlite_3_38.SqliteDialect()
                val environment = SqlDelightEnvironment(properties, unit, false, dialect, module)
                when (val generated = environment.generateSqlDelightFiles(::println)) {
                    is SqlDelightEnvironment.CompilationStatus.Failure -> {
                        status = "compilation-error"; code = 1
                        diagnostics = generated.errors.asSequence().map { problem ->
                            val path = Path.of(problem.path).toAbsolutePath().normalize()
                            require(path.startsWith(sourceRoot) && path in sources) { "SQL diagnostic refers to an undeclared source" }
                            diagnostic("KVIK_SQLDELIGHT_COMPILATION", "compilation", problem.message.take(16384), inputRoot.relativize(path).toString(), problem.line, problem.column)
                        }
                    }
                    SqlDelightEnvironment.CompilationStatus.Success -> Unit
                }
            }
            workerResult(requestId, status, code, diagnostics)
        } catch (failure: Exception) {
            code = 2
            workerResult(requestId, "infrastructure-error", code,
                sequenceOf(diagnostic("KVIK_SQLDELIGHT_WORKER", "infrastructure", (failure.message ?: failure.javaClass.name).take(16384))))
        }
        if (code != 0) Files.walk(outputRoot).use { paths -> paths.sorted(Comparator.reverseOrder()).filter { it != outputRoot }.forEach(Files::delete) }

        Files.writeString(Path.of(resultFile), result, StandardOpenOption.CREATE_NEW)
        exitProcess(code)
    }
}


/** Includes escaped paths, request identity, separators and an explicit truncation marker in the byte budget. */
internal fun workerResult(requestId: String, status: String, code: Int, diagnostics: Sequence<String>): String {
    val prefix = "{\"schemaVersion\":1,\"requestId\":" + quote(requestId) + ",\"status\":" + quote(status) + ",\"exitCode\":" + code + ",\"diagnostics\":["
    val suffix = "]}"
    val marker = diagnostic("KVIK_SQLDELIGHT_DIAGNOSTICS_TRUNCATED", if (status == "compilation-error") "compilation" else "infrastructure",
        "Additional SQLDelight diagnostics were omitted to keep the worker result within its size limit")
    val limit = 2 * 1024 * 1024
    var bytes = prefix.toByteArray(Charsets.UTF_8).size + suffix.length
    val reservedMarker = marker.toByteArray(Charsets.UTF_8).size + 1
    require(bytes + reservedMarker <= limit) { "Oversized SQLDelight request identity" }
    val selected = mutableListOf<String>()
    for (entry in diagnostics) {
        val addedBytes = entry.toByteArray(Charsets.UTF_8).size + if (selected.isEmpty()) 0 else 1
        if (selected.size == 1000 || bytes.toLong() + addedBytes + reservedMarker > limit) {
            selected += marker
            break
        }
        selected += entry
        bytes += addedBytes
    }
    return prefix + selected.joinToString(",") + suffix
}

private fun diagnostic(code: String, category: String, message: String, path: String? = null, line: Int? = null, column: Int? = null): String =
    "{\"code\":" + quote(code) + ",\"category\":" + quote(category) + ",\"message\":" + quote(message) +
        ",\"actionId\":null,\"module\":null,\"target\":null,\"path\":" + (path?.let(::quote) ?: "null") +
        ",\"line\":" + (line ?: "null") + ",\"column\":" + (column ?: "null") + "}"

private fun quote(text: String): String = buildString {
    append('"')
    for (char in text) when (char) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (char.code < 32) append("\\u" + char.code.toString(16).padStart(4, '0')) else append(char)
    }
    append('"')
}

private data class SourceFolder(override val folder: File, override val dependency: Boolean) : SqlDelightSourceFolder
private data class CompilationUnit(override val name: String, override val sourceFolders: Set<SqlDelightSourceFolder>, override val outputDirectoryFile: File) : SqlDelightCompilationUnit
private data class DatabaseProperties(
    override val packageName: String,
    override val compilationUnits: List<SqlDelightCompilationUnit>,
    override val className: String,
    override val dependencies: List<SqlDelightDatabaseName>,
    override val deriveSchemaFromMigrations: Boolean,
    override val treatNullAsUnknownForEquality: Boolean,
    override val rootDirectory: File,
    override val generateAsync: Boolean,
    override val expandSelectStar: Boolean,
) : SqlDelightDatabaseProperties
""".trimStart(),
)
