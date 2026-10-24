package kvik.core

import kotlin.test.*

class ScriptDeclarationsTest {
    @Test fun leadingByteOrderMarkDoesNotHideDeclarations() {
        assertEquals(listOf("g:a:1"), parseScriptDeclarations("\uFEFF@file:DependsOn(\"g:a:1\")\nprintln(1)").single().values)
        assertEquals(listOf("g:a:1"), parseScriptDeclarations("\uFEFF#!/usr/bin/env kvik\n@file:DependsOn(\"g:a:1\")").single().values)
    }

    @Test fun extractsOrderedLiteralDeclarationsAndSourcePositions() {
        val declarations = parseScriptDeclarations("""
            #!/usr/bin/env kvik
            /* header /* nested */ comment */
            @file:DependsOn("g:first:1", "g:second:2",)
            @file:Repository(urls = ["https://example.test/maven",])
            @file:Import(paths = ["helper.kts", "more.kts"])
            println("body")
        """.trimIndent())
        assertEquals(listOf(
            ScriptDeclaration("DependsOn", listOf("g:first:1", "g:second:2"), 3, 1),
            ScriptDeclaration("Repository", listOf("https://example.test/maven"), 4, 1),
            ScriptDeclaration("Import", listOf("helper.kts", "more.kts"), 5, 1),
        ), declarations)
    }

    @Test fun supportsGroupsQualifiedNamesAliasesAndHeaderPackage() {
        val declarations = parseScriptDeclarations("""
            @file:[
                kvik /* x */ . jvm . DependsOn(coordinates = ["g:a:1"])
                Repo("https://example.test")
                Include("helper.kts")
            ]
            package example.scripts
            import kvik.jvm.Repository as Repo
            import kvik.jvm.Import as Include;
            val body = 1
        """.trimIndent())
        assertEquals(listOf("DependsOn", "Repository", "Import"), declarations.map { it.annotation })
        assertEquals(listOf(listOf("g:a:1"), listOf("https://example.test"), listOf("helper.kts")), declarations.map { it.values })
        assertEquals(2, declarations.first().line)
        assertEquals(5, declarations.first().column)
    }

    @Test fun decodesEscapesAndPreservesRawStringContents() {
        val triple = "\"\"\""
        val declarations = parseScriptDeclarations(
            "@file:Import(\"a\\t\\b\\n\\r\\'\\\"\\\\\\\$\\u0062\", $triple" +
                "first\\line\nsecond\$ literal" + "$triple)"
        )
        assertEquals(listOf("a\t\b\n\r'\"\\\$b", "first\\line\nsecond\$ literal"), declarations.single().values)
    }

    @Test fun escapedDollarDoesNotInterpolateAndLiteralDollarIsAllowed() {
        assertEquals(listOf("\$name", "\$", "\$1"), parseScriptDeclarations(
            "@file:Import(\"\\\$name\", \"\$\", \"\$1\")"
        ).single().values)
    }

    @Test fun ignoresAnnotationsInCommentsStringsAndExecutableBody() {
        val source = """
            // @file:DependsOn("g:comment:1")
            /* @file:DependsOn("g:block:1") */
            val text = "@file:DependsOn(\"g:string:1\")"
            @file:DependsOn("g:late:1")
        """.trimIndent()
        assertTrue(parseScriptDeclarations(source).isEmpty())
        assertTrue(parseScriptDeclarations("@DependsOn(\"g:ordinary:1\")\nval x = 1").isEmpty())
        assertTrue(parseScriptDeclarations("fun f() { @file:DependsOn(\"g:body:1\") }").isEmpty())
        assertTrue(parseScriptDeclarations("package example\n@file:DependsOn(\"g:late:1\")").isEmpty())
    }

    @Test fun leavesArbitraryBodySyntaxToCompiler() {
        val header = "@file:DependsOn(\"g:a:1\")\n"
        for (body in listOf("val broken = \"", "\"\"\"unterminated", "fun f() = { (((", "\$\$\"multi-dollar body")) {
            assertEquals(listOf("g:a:1"), parseScriptDeclarations(header + body).single().values)
        }
    }

    @Test fun foreignImportsShadowDefaultNamesAndAliases() {
        assertTrue(parseScriptDeclarations("""
            @file:DependsOn(computeAnything())
            @file:Other("g:foreign:1")
            import another.DependsOn
            import another.Import as Other
        """.trimIndent()).isEmpty())
        assertEquals("Repository", parseScriptDeclarations("""
            @file:DependsOn("https://example.test")
            import kvik.jvm.Repository as DependsOn
        """.trimIndent()).single().annotation)
    }

    @Test fun foreignQualifiedAnnotationsAreNeverRecognized() {
        assertTrue(parseScriptDeclarations("""
            @file:other.DependsOn("g:foreign:1")
            @file:notkvik.jvm.Import("foreign.kts")
        """.trimIndent()).isEmpty())
    }

    @Test fun rejectsAmbiguousImportsBeforeProducingDeclarations() {
        for (imports in listOf(
            "import other.*",
            "import kvik.jvm.*\nimport other.*",
            "import kvik.jvm.DependsOn\nimport other.DependsOn",
        )) assertInvalid("@file:DependsOn(\"g:a:1\")\n$imports", "Ambiguous")
        assertInvalid("@file:Alias(\"g:a:1\")\nimport kvik.jvm.DependsOn as Alias\nimport other.Type as Alias", "Ambiguous")
    }

    @Test fun explicitAndQualifiedNamesDisambiguateWildcards() {
        for (source in listOf(
            "@file:DependsOn(\"g:a:1\")\nimport kvik.jvm.DependsOn\nimport other.*",
            "@file:kvik.jvm.DependsOn(\"g:a:1\")\nimport other.*",
            "@file:DependsOn(\"g:a:1\")\nimport kvik.jvm.*",
            "@file:DependsOn(\"g:a:1\")\nimport kvik.jvm.DependsOn\nimport kvik.jvm.DependsOn",
        )) assertEquals(listOf("g:a:1"), parseScriptDeclarations(source).single().values)
    }

    @Test fun acceptsCommentsAndQuotedNamesInImportDirectives() {
        val source = """
            @file:`Deps`("g:a:1")
            import kvik /* namespace */ . jvm . `DependsOn` as `Deps`
        """.trimIndent()
        assertEquals("DependsOn", parseScriptDeclarations(source).single().annotation)
    }

    @Test fun acceptsEmptyVarargsAndArrays() {
        for (annotation in listOf("DependsOn", "Repository", "Import")) {
            assertTrue(parseScriptDeclarations("@file:$annotation()").single().values.isEmpty())
            assertTrue(parseScriptDeclarations("@file:$annotation").single().values.isEmpty())
        }
        assertTrue(parseScriptDeclarations("@file:DependsOn(coordinates = [],)").single().values.isEmpty())
    }

    @Test fun rejectsExecutableArgumentsAndWrongNamedParameters() {
        val dollar = '$'
        val triple = "\"\"\""
        for (argument in listOf(
            "CONSTANT", "compute()", "*arrayOf(\"g:a:1\")", "\"g:a:\" + \"1\"",
            "\"${dollar}version\"", "\"${dollar}{version()}\"", "$triple${dollar}version$triple",
            "coordinates = arrayOf(\"g:a:1\")", "coordinates = [CONSTANT]", "coordinates = [*values]",
            "urls = [\"g:a:1\"]", "coordinates = \"g:a:1\"", "\"g:a:1\", coordinates = [\"g:b:1\"]",
            "coordinates = [\"g:a:1\"], coordinates = [\"g:b:1\"]", "[\"g:a:1\"]",
        )) assertInvalid("@file:DependsOn($argument)")
    }

    @Test fun skipsUnrelatedNestedAnnotationExpressionsAndTemplateStrings() {
        val dollar = '$'
        val source = "@file:Suppress(\"${dollar}{something(\"x\")}\")\n" +
            "@file:Other(call(listOf(1, 2), { (3) }))\n@file:DependsOn(\"g:a:1\")"
        assertEquals(listOf("g:a:1"), parseScriptDeclarations(source).single().values)
    }

    @Test fun diagnosticIdentifiesArgumentLocationIncludingCrLf() {
        val failure = assertFailsWith<IllegalArgumentException> {
            parseScriptDeclarations("// header\r\n@file:DependsOn(\r\n  \"g:a:1\",\r\n  dynamic()\r\n)")
        }
        assertContains(failure.message.orEmpty(), "line 4, column 3")
    }

    @Test fun rejectsMalformedRecognizedLiteralsAndDelimiters() {
        for (source in listOf(
            "@file:DependsOn(\"unterminated)", "@file:DependsOn(\"bad\\q\")",
            "@file:DependsOn(\"bad\\u00zz\")", "@file:DependsOn(\"bad\nnewline\")",
            "@file:DependsOn(coordinates = [\"a\")", "@file:[DependsOn(\"a\")",
            "@file:DependsOn(\"a\"\"b\")", "@file:DependsOn(\"a\",,)",
        )) assertInvalid(source)
    }

    @Test fun boundsSourceSizeUsingUtf8Bytes() {
        assertTrue(parseScriptDeclarations("x".repeat(4 * 1024 * 1024)).isEmpty())
        assertInvalid("x".repeat(4 * 1024 * 1024 + 1), "4 MiB")
        assertInvalid("\u0800".repeat(4 * 1024 * 1024 / 3 + 1), "4 MiB")
    }

    @Test fun boundsDecodedLiteralsValuesAndAnnotations() {
        assertEquals(4096, parseScriptDeclarations("@file:Import(\"${"a".repeat(4096)}\")").single().values.single().length)
        assertInvalid("@file:Import(\"${"a".repeat(4097)}\")", "4096")
        assertEquals(1024, parseScriptDeclarations("@file:Import(${List(1024) { "\"x\"" }.joinToString()})").single().values.size)
        assertInvalid("@file:Import(${List(1025) { "\"x\"" }.joinToString()})", "1024")
        assertInvalid("@file:Import(\"x\")\n".repeat(1025), "1024")
        assertInvalid("@file:Suppress()\n".repeat(1025), "1024")
        assertInvalid("@file:Import(\"x\")\n" + "import example.Type\n".repeat(1025), "1024")
    }

    @Test fun boundsHeaderNestingWithoutStackOverflow() {
        assertInvalid("/*".repeat(65) + "*/".repeat(65), "nesting")
        assertInvalid("@file:Other(" + "(".repeat(65) + "0" + ")".repeat(66), "nesting")
    }

    private fun assertInvalid(source: String, message: String? = null) {
        val failure = assertFailsWith<IllegalArgumentException>(source.take(120)) { parseScriptDeclarations(source) }
        assertContains(failure.message.orEmpty(), "line ")
        assertContains(failure.message.orEmpty(), "column ")
        if (message != null) assertContains(failure.message.orEmpty(), message)
    }
}
