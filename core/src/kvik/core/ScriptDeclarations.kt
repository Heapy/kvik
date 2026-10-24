package kvik.core

/** A literal script declaration. Locations are one-based; grouped entries point to their name. */
data class ScriptDeclaration(val annotation: String, val values: List<String>, val line: Int, val column: Int)

/**
 * Reads only leading Kotlin file annotations and the following package/import header, without executing code.
 * Recognizes DependsOn, Repository and Import from kvik.jvm, including explicit imports and aliases. Their
 * short names are available by default; explicit foreign imports shadow them and unresolved wildcard
 * collisions fail closed. Qualified names may contain whitespace/comments around dots.
 *
 * Arguments must be ordinary escaped or triple-quoted strings, supplied as positional varargs or one
 * named array literal (coordinates, urls or paths respectively). Trailing commas are accepted. Constants,
 * interpolation, calls, spread arguments and concatenation are deliberately unsupported. This is a bounded
 * declaration extractor, not a Kotlin syntax/type checker: the script body is left to the compiler.
 * Limits: 4 MiB UTF-8 source, 1024 header annotations/imports, 1024 total declaration values, 4096 UTF-16
 * code units per decoded literal, and 64 levels of nesting in header expressions/comments.
 */
fun parseScriptDeclarations(source: String): List<ScriptDeclaration> = ScriptDeclarationParser(source).parse()

private const val SCRIPT_SOURCE_LIMIT = 4 * 1024 * 1024
private const val SCRIPT_ITEM_LIMIT = 1024
private const val SCRIPT_LITERAL_LIMIT = 4096
private const val SCRIPT_NESTING_LIMIT = 64
private val scriptAnnotationParameters = mapOf("DependsOn" to "coordinates", "Repository" to "urls", "Import" to "paths")

private data class ScriptPosition(val offset: Int, val line: Int, val column: Int)
private data class RawScriptAnnotation(val name: String, val position: ScriptPosition, val arguments: ScriptPosition?, val end: Int)

private class ScriptDeclarationParser(private val source: String) {
    private val cursor = ScriptHeaderCursor(source)
    private val imports = mutableMapOf<String, MutableSet<String>>()
    private var foreignWildcard = false
    private var valueCount = 0

    fun parse(): List<ScriptDeclaration> {
        checkSourceSize()
        if (cursor.char == '\uFEFF') cursor.advance()
        if (cursor.starts("#!")) while (!cursor.done && cursor.char != '\n' && cursor.char != '\r') cursor.advance()
        cursor.trivia()
        val annotations = mutableListOf<RawScriptAnnotation>()
        while (cursor.char == '@') {
            val start = cursor.position()
            cursor.advance()
            cursor.trivia()
            if (cursor.identifierOrNull() != "file") return resolve(annotations)
            cursor.trivia()
            if (cursor.char != ':') return resolve(annotations)
            cursor.advance()
            cursor.trivia()
            if (cursor.char == '[') {
                cursor.advance()
                cursor.trivia()
                while (cursor.char != ']') {
                    if (cursor.done) cursor.fail("Unterminated file annotation group")
                    annotations += annotation(cursor.position())
                    if (annotations.size > SCRIPT_ITEM_LIMIT) cursor.fail("Too many file annotations (maximum 1024)")
                    cursor.trivia()
                }
                cursor.advance()
            } else {
                annotations += annotation(start)
                if (annotations.size > SCRIPT_ITEM_LIMIT) cursor.fail("Too many file annotations (maximum 1024)")
            }
            cursor.trivia()
        }
        if (cursor.keyword("package")) {
            cursor.consumeKeyword("package")
            cursor.trivia()
            cursor.qualifiedName()
            directiveEnd()
        }
        var importCount = 0
        while (cursor.keyword("import")) {
            if (++importCount > SCRIPT_ITEM_LIMIT) cursor.fail("Too many imports (maximum 1024)")
            cursor.consumeKeyword("import")
            cursor.trivia()
            val name = cursor.qualifiedName(allowWildcard = true)
            val nameEndLine = cursor.line
            cursor.trivia()
            var alias: String? = null
            if (cursor.keyword("as")) {
                cursor.consumeKeyword("as")
                cursor.trivia()
                alias = cursor.identifierOrNull() ?: cursor.fail("Expected import alias")
                if (name.endsWith(".*")) cursor.fail("Wildcard imports cannot have aliases")
                directiveEnd()
            } else {
                directiveEnd(nameEndLine)
            }
            if (name.endsWith(".*")) {
                if (name != "kvik.jvm.*") foreignWildcard = true
            } else {
                imports.getOrPut(alias ?: name.substringAfterLast('.')) { mutableSetOf() }.add(name)
            }
        }
        return resolve(annotations)
    }

    private fun annotation(position: ScriptPosition): RawScriptAnnotation {
        val name = cursor.qualifiedName()
        cursor.trivia()
        if (cursor.char != '(') return RawScriptAnnotation(name, position, null, cursor.offset)
        cursor.advance()
        val arguments = cursor.position()
        cursor.expressionUntil(')', 0)
        val end = cursor.offset
        cursor.advance()
        return RawScriptAnnotation(name, position, arguments, end)
    }

    private fun directiveEnd(lastLine: Int = cursor.line) {
        cursor.trivia()
        if (cursor.char == ';') {
            cursor.advance()
            cursor.trivia()
        } else if (!cursor.done && cursor.line == lastLine) {
            cursor.fail("Expected newline or semicolon after header directive")
        }
    }

    private fun resolve(annotations: List<RawScriptAnnotation>): List<ScriptDeclaration> = annotations.mapNotNull { raw ->
        val normalized = if ('.' in raw.name) {
            raw.name.removePrefix("kvik.jvm.").takeIf { raw.name == "kvik.jvm.$it" && it in scriptAnnotationParameters }
        } else {
            val candidates = imports[raw.name]
            if (candidates != null) {
                if (candidates.size > 1 && (raw.name in scriptAnnotationParameters || candidates.any { it.removePrefix("kvik.jvm.") in scriptAnnotationParameters })) {
                    cursor.fail("Ambiguous script annotation ${raw.name}", raw.position)
                }
                candidates.singleOrNull()?.removePrefix("kvik.jvm.")?.takeIf {
                    candidates.single() == "kvik.jvm.$it" && it in scriptAnnotationParameters
                }
            } else {
                raw.name.takeIf { it in scriptAnnotationParameters }?.also {
                    if (foreignWildcard) cursor.fail("Ambiguous script annotation $it; use an explicit kvik.jvm import or qualified name", raw.position)
                }
            }
        } ?: return@mapNotNull null
        ScriptDeclaration(normalized, arguments(raw, scriptAnnotationParameters.getValue(normalized)), raw.position.line, raw.position.column)
    }

    private fun arguments(raw: RawScriptAnnotation, parameter: String): List<String> {
        val start = raw.arguments ?: return emptyList()
        val input = ScriptHeaderCursor(source, start, raw.end)
        input.trivia()
        if (input.done) return emptyList()
        val values = mutableListOf<String>()
        if (input.char != '"') {
            val name = input.identifierOrNull() ?: input.fail("Expected a literal string or named $parameter array")
            if (name != parameter) input.fail("Expected named parameter $parameter")
            input.trivia()
            input.expect('=')
            input.trivia()
            input.expect('[')
            readValues(input, values, ']')
            input.expect(']')
            input.trivia()
            if (input.char == ',') { input.advance(); input.trivia() }
            if (!input.done) input.fail("Only one named $parameter array is supported")
        } else {
            readValues(input, values, null)
        }
        return values
    }

    private fun readValues(input: ScriptHeaderCursor, values: MutableList<String>, closing: Char?) {
        input.trivia()
        if (input.done || input.char == closing) return
        while (true) {
            if (input.char != '"') input.fail("Script declaration arguments must be literal strings")
            values += input.literal()
            if (++valueCount > SCRIPT_ITEM_LIMIT) input.fail("Too many script declaration values (maximum 1024)")
            input.trivia()
            if (input.done || input.char == closing) return
            input.expect(',')
            input.trivia()
            if (input.done || input.char == closing) return
        }
    }

    private fun checkSourceSize() {
        if (source.length > SCRIPT_SOURCE_LIMIT) cursor.fail("Script source exceeds 4 MiB")
        var bytes = 0
        var index = 0
        while (index < source.length) {
            val char = source[index++]
            bytes += when {
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                char.isHighSurrogate() && index < source.length && source[index].isLowSurrogate() -> { index++; 4 }
                else -> 3
            }
            if (bytes > SCRIPT_SOURCE_LIMIT) cursor.fail("Script source exceeds 4 MiB")
        }
    }
}

private class ScriptHeaderCursor(
    private val source: String,
    start: ScriptPosition = ScriptPosition(0, 1, 1),
    private val limit: Int = source.length,
) {
    var offset = start.offset
        private set
    var line = start.line
        private set
    private var column = start.column
    val done: Boolean get() = offset >= limit
    val char: Char? get() = source.getOrNull(offset)?.takeIf { offset < limit }
    fun position() = ScriptPosition(offset, line, column)
    fun starts(text: String) = offset + text.length <= limit && source.startsWith(text, offset)
    fun advance(count: Int = 1) {
        repeat(count) {
            if (done) fail("Unexpected end of script header")
            val current = source[offset++]
            if (current == '\r' || (current == '\n' && (offset < 2 || source[offset - 2] != '\r'))) {
                line++
                column = 1
            } else if (current != '\n') column++
        }
    }
    fun fail(message: String, at: ScriptPosition = position()): Nothing =
        throw IllegalArgumentException("$message at line ${at.line}, column ${at.column}")

    fun trivia() {
        while (true) {
            when {
                char?.isWhitespace() == true -> advance()
                starts("//") -> while (!done && char != '\n' && char != '\r') advance()
                starts("/*") -> {
                    val start = position()
                    advance(2)
                    var depth = 1
                    while (depth > 0) {
                        when {
                            done -> fail("Unterminated block comment", start)
                            starts("/*") -> {
                                if (++depth > SCRIPT_NESTING_LIMIT) fail("Header comment nesting exceeds 64")
                                advance(2)
                            }
                            starts("*/") -> { depth--; advance(2) }
                            else -> advance()
                        }
                    }
                }
                else -> return
            }
        }
    }

    fun keyword(word: String) = starts(word) && source.getOrNull(offset + word.length)?.let { !identifierPart(it) } != false
    fun consumeKeyword(word: String) { if (!keyword(word)) fail("Expected $word"); advance(word.length) }
    fun identifierOrNull(): String? {
        val start = offset
        if (char == '`') {
            advance()
            val content = offset
            while (!done && char != '`' && char != '\n' && char != '\r') advance()
            if (char != '`' || content == offset) fail("Invalid quoted identifier")
            val result = source.substring(content, offset)
            advance()
            return result
        }
        if (char?.let { it == '_' || it.isLetter() } != true) return null
        advance()
        while (char?.let(::identifierPart) == true) advance()
        return source.substring(start, offset)
    }

    fun qualifiedName(allowWildcard: Boolean = false): String {
        val result = StringBuilder(identifierOrNull() ?: fail("Expected annotation or package name"))
        while (true) {
            // Keep trailing trivia unconsumed so directiveEnd can distinguish a newline.
            val saved = position()
            trivia()
            if (char != '.') {
                offset = saved.offset; line = saved.line; column = saved.column
                return result.toString()
            }
            advance()
            trivia()
            result.append('.')
            if (allowWildcard && char == '*') { advance(); result.append('*'); return result.toString() }
            result.append(identifierOrNull() ?: fail("Expected name after dot"))
        }
    }

    fun expect(expected: Char) {
        if (char != expected) fail("Expected '$expected' in script declaration")
        advance()
    }

    /** Skip unrelated annotation expressions, including strings containing nested template expressions. */
    fun expressionUntil(closing: Char, depth: Int) {
        if (depth > SCRIPT_NESTING_LIMIT) fail("Header expression nesting exceeds 64")
        while (true) {
            trivia()
            when (char) {
                null -> fail("Unterminated annotation expression; expected '$closing'")
                closing -> return
                '(' -> { advance(); expressionUntil(')', depth + 1); advance() }
                '[' -> { advance(); expressionUntil(']', depth + 1); advance() }
                '{' -> { advance(); expressionUntil('}', depth + 1); advance() }
                ')', ']', '}' -> fail("Mismatched annotation delimiter")
                '"', '\'' -> skipQuoted(depth)
                else -> advance()
            }
        }
    }

    private fun skipQuoted(depth: Int) {
        val start = position()
        val quote = char!!
        val raw = quote == '"' && starts("\"\"\"")
        advance(if (raw) 3 else 1)
        while (!done) {
            when {
                raw && starts("\"\"\"") -> { advance(3); return }
                !raw && char == quote -> { advance(); return }
                !raw && char == '\\' -> { advance(); if (!done) advance() }
                quote == '"' && starts("\${") -> { advance(2); expressionUntil('}', depth + 1); advance() }
                !raw && (char == '\n' || char == '\r') -> fail("Newline in quoted header literal", start)
                else -> advance()
            }
        }
        fail("Unterminated header literal", start)
    }

    fun literal(): String {
        val start = position()
        val raw = starts("\"\"\"")
        expect('"')
        if (raw) advance(2)
        val result = StringBuilder()
        while (!done) {
            when {
                raw && starts("\"\"\"") -> { advance(3); return result.toString() }
                !raw && char == '"' -> { advance(); return result.toString() }
                char == '$' && source.getOrNull(offset + 1)?.let { it == '{' || it == '`' || it == '_' || it.isLetter() } == true ->
                    fail("String interpolation is unsupported in script declarations")
                !raw && char == '\\' -> {
                    advance()
                    val escaped = when (val escape = char) {
                        't' -> '\t'; 'b' -> '\b'; 'n' -> '\n'; 'r' -> '\r'; '\'' -> '\''; '"' -> '"'; '\\' -> '\\'; '$' -> '$'
                        'u' -> {
                            advance()
                            var code = 0
                            repeat(4) {
                                val digit = char?.digitToIntOrNull(16) ?: fail("Expected four hexadecimal digits after \\u")
                                code = code * 16 + digit
                                advance()
                            }
                            result.append(code.toChar())
                            if (result.length > SCRIPT_LITERAL_LIMIT) fail("Script literal exceeds 4096 characters", start)
                            continue
                        }
                        else -> fail("Unsupported string escape: $escape")
                    }
                    advance()
                    result.append(escaped)
                }
                !raw && (char == '\n' || char == '\r') -> fail("Newline in ordinary string literal", start)
                else -> { result.append(char!!); advance() }
            }
            if (result.length > SCRIPT_LITERAL_LIMIT) fail("Script literal exceeds 4096 characters", start)
        }
        fail("Unterminated script declaration literal", start)
    }

    private fun identifierPart(char: Char) = char == '_' || char.isLetterOrDigit()
}
