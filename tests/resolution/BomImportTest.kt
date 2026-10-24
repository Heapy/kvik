@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.resolution

import kvik.core.*
import kotlinx.cinterop.*
import platform.posix.*
import kotlin.test.*

class BomImportTest {
    private val parser = NativePomParser()
    private val api = jvmVariantRequest("java-api", 11)
    private val runtime = jvmVariantRequest("java-runtime", 11)
    private fun c(name: String) = Coordinate("example", name, "1")
    private fun text(name: String, body: String = "") = "<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>$name</artifactId><version>1</version>$body</project>"
    private fun pom(name: String, body: String = "") = parser.parse(text(name, body).encodeToByteArray())
    private fun dependency(name: String, fields: String = "") = "<dependency><groupId>example</groupId><artifactId>$name</artifactId>$fields</dependency>"
    private fun dependencies(vararg entries: String) = "<dependencies>${entries.joinToString("")}</dependencies>"
    private fun management(vararg entries: String) = "<dependencyManagement>${dependencies(*entries)}</dependencyManagement>"
    private fun bom(name: String) = dependency(name, "<version>1</version><type>pom</type><scope>import</scope>")
    private fun parent(name: String) = "<parent><groupId>example</groupId><artifactId>$name</artifactId><version>1</version></parent>"

    @Test fun explicitAndInheritedManagementWinBeforeOrderedBomImports() {
        val models = mapOf(
            c("parent") to pom("parent", "<packaging>pom</packaging>" + management(dependency("parentManaged", "<version>2</version>"))),
            c("first") to pom("first", "<packaging>pom</packaging><properties><chosen>3</chosen></properties>" + management(
                dependency("shared", "<version>\${chosen}</version><scope>runtime</scope>"), dependency("parentManaged", "<version>3</version>"), dependency("local", "<version>3</version>"))),
            c("second") to pom("second", "<packaging>pom</packaging>" + management(dependency("shared", "<version>4</version>"), dependency("secondOnly", "<version>4</version>"))))
        val root = pom("root", parent("parent") + "<properties><chosen>99</chosen></properties>" +
            management(bom("first"), bom("second"), dependency("local", "<version>5</version>")) +
            dependencies(dependency("shared"), dependency("parentManaged"), dependency("local"), dependency("secondOnly")))
        val result = effectivePom(c("root"), root, models::getValue)
        assertEquals(listOf("example:shared:3", "example:parentManaged:2", "example:local:5", "example:secondOnly:4"), result.dependencies(runtime).map(Coordinate::toString))
        assertEquals(listOf("parentManaged", "local", "secondOnly"), result.dependencies(api).map { it.name })
        assertTrue(result.management.values.none { it["scope"]?.text == "import" })
    }

    @Test fun nestedImportsUseBomParentsAndPropertiesWithoutLeakingTheirDependencies() {
        val models = mapOf(
            c("commonParent") to pom("commonParent", "<packaging>pom</packaging><properties><managed.version>7</managed.version></properties>"),
            c("bom") to pom("bom", parent("commonParent") + "<packaging>pom</packaging>" + management(bom("nested")) + dependencies(dependency("doNotInherit", "<version>1</version>"))),
            c("nested") to pom("nested", parent("commonParent") + "<packaging>pom</packaging>" + management(dependency("b", "<version>\${managed.version}</version>"))))
        val fetched = mutableListOf<Coordinate>()
        val root = pom("root", parent("commonParent") + management(bom("bom")) + dependencies(dependency("b")))
        assertEquals(listOf(Coordinate("example", "b", "7")), effectivePom(c("root"), root) { fetched += it; models.getValue(it) }.dependencies(runtime))
        assertEquals(fetched.distinct(), fetched) // A shared parent is fetched once and is not a cycle.
        assertEquals(setOf(c("commonParent"), c("bom"), c("nested")), fetched.toSet())
    }

    @Test fun inheritedImportCoordinatesUseTheChildPropertyContext() {
        val inheritedImport = bom("bom").replace("<version>1</version>", "<version>\${chosen}</version>")
        val fetched = mutableListOf<Coordinate>()
        val result = effectivePom(c("root"), pom("root", parent("p") + "<properties><chosen>2</chosen></properties>" + dependencies(dependency("b")))) { requested ->
            fetched += requested
            if (requested == c("p")) pom("p", "<packaging>pom</packaging><properties><chosen>1</chosen></properties>" + management(inheritedImport))
            else {
                assertEquals(Coordinate("example", "bom", "2"), requested)
                parser.parse(text("bom", "<packaging>pom</packaging>" + management(dependency("b", "<version>7</version>")))
                    .replace("<artifactId>bom</artifactId><version>1</version>", "<artifactId>bom</artifactId><version>2</version>").encodeToByteArray())
            }
        }
        assertEquals(listOf(Coordinate("example", "b", "7")), result.dependencies(runtime))
        assertTrue(Coordinate("example", "bom", "1") !in fetched)
    }

    @Test fun importCyclesBoundsMalformedDeclarationsAndMutableVersionsFailClosed() {
        val cycle = mapOf(c("a") to pom("a", "<packaging>pom</packaging>" + management(bom("b"))),
            c("b") to pom("b", "<packaging>pom</packaging>" + management(bom("a"))))
        assertFails { effectivePom(c("a"), cycle.getValue(c("a")), cycle::getValue) }
        assertFails { effectivePom(c("root"), pom("root", management(bom("b")))) { pom("b", "<packaging>pom</packaging>" + parent("root")) } }
        assertFails { effectivePom(c("root"), pom("root", management(bom("b")))) { pom("b") } }
        assertFails { effectivePom(c("root"), pom("root", management(bom("b")))) { pom("wrong", "<packaging>pom</packaging>") } }
        for (declaration in listOf(bom("b").replace("<type>pom</type>", "<type>jar</type>"),
            bom("b").replace("<version>1</version>", "<version>1-snapshot</version>"),
            bom("b").replace("</dependency>", "<optional>true</optional></dependency>"))) {
            assertFails { effectivePom(c("root"), pom("root", management(declaration))) { error("Malformed import must fail before fetch") } }
        }
        assertFails { effectivePom(c("root"), pom("root", management(bom("b0")))) { requested ->
            val next = requested.name.removePrefix("b").toInt() + 1
            pom(requested.name, "<packaging>pom</packaging>" + management(bom("b$next")))
        } }
        assertFails { effectivePom(c("root"), pom("root", management(*(0..256).map { bom("bom$it") }.toTypedArray()))) { requested ->
            pom(requested.name, "<packaging>pom</packaging>")
        } }
    }

    @Test fun testOnlyProfilesCannotRemoveConsumerDependenciesEvenThroughPropertiesOrInheritance() {
        fun profile(entry: String) = "<profiles><profile><id>native-tests</id>${dependencies(entry)}</profile></profiles>"
        val test = dependency("b", "<scope>test</scope><version>1</version><exclusions><exclusion><groupId>x</groupId><artifactId>y</artifactId></exclusion></exclusions>")
        assertTrue(effectivePom(c("root"), pom("root", profile(test))) { error("No fetch") }.dependencies(runtime).isEmpty())
        val localTest = dependencies(dependency("b", "<scope>test</scope><version>1</version>"))
        assertTrue(effectivePom(c("root"), pom("root", localTest + profile(test))) { error("No fetch") }.dependencies(runtime).isEmpty())
        for (scope in listOf("compile", "runtime")) {
            val consumer = dependencies(dependency("b", "<scope>$scope</scope><version>1</version>"))
            assertFails { effectivePom(c("root"), pom("root", consumer + profile(test))) { error("No fetch") } }
            assertFails { effectivePom(c("root"), pom("root", parent("p") + profile(test))) { pom("p", "<packaging>pom</packaging>$consumer") } }
        }
        assertFails {
            effectivePom(c("root"), pom("root", management(bom("bom")) + dependencies(dependency("b")) + profile(test))) {
                pom("bom", "<packaging>pom</packaging>" + management(dependency("b", "<scope>runtime</scope><version>1</version>")))
            }
        }
        val propertyIdentity = test.replace("<artifactId>b</artifactId>", "<artifactId>\${identity}</artifactId>")
        assertFails { effectivePom(c("root"), pom("root", "<properties><identity>b</identity></properties>" +
            dependencies(dependency("b", "<version>1</version>")) + profile(propertyIdentity))) { error("No fetch") } }
        assertFails { effectivePom(c("root"), pom("root", profile(dependency("b", "<scope>runtime</scope><version>1</version>")))) { error("No fetch") } }
    }

    @Test fun importedBomMetadataIsRetainedForOfflineReplayAndMutationChecks() {
        val repo = Repository("central", "https://example.test/maven")
        val rootPom = text("root", management(bom("bom")) + dependencies(dependency("b")))
        val bomPom = text("bom", "<packaging>pom</packaging>" + management(dependency("b", "<version>1</version>")))
        val documents = mapOf("root" to rootPom, "bom" to bomPom, "b" to text("b"))
        val fetched = mutableMapOf<Digest, ByteArray>()
        val transport = object : ArtifactTransport {
            override fun fetch(url: String): ByteArray {
                if (url.endsWith(".module")) throw MissingResource(url)
                val name = url.substringBeforeLast('/').substringBeforeLast('/').substringAfterLast('/')
                return (if (url.endsWith(".pom")) documents.getValue(name).encodeToByteArray() else name.encodeToByteArray())
                    .also { fetched[Sha256.digest(it)] = it }
            }
        }
        val selected = MavenResolver(transport, parser).resolve(c("root"), runtime, listOf(repo))
        assertEquals(setOf(c("root"), c("b")), selected.artifacts.map { it.coordinate }.toSet())
        val imported = selected.metadata.single { it.url.endsWith("/bom/1/bom-1.pom") }
        verifyLocked(selected) { fetched[it] }
        assertFails { verifyLocked(selected) { if (it == imported.digest) bomPom.replace("<version>1</version>", "<version>2</version>").encodeToByteArray() else fetched[it] } }
        assertFails { MavenResolver(transport, parser, maxComponents = 1).resolve(c("root"), runtime, listOf(repo)) }
    }

    @Test fun actualSqliteJdbcPomImportsJunitBomAndIgnoresOnlyNonconsumerProfileChanges() {
        fun fixture(name: String): PomNode {
            val root = getenv("KVIK_RESOLUTION_ROOT")!!.toKString()
            val file = fopen("$root/tests/resolution/fixtures/$name", "rb") ?: error("Missing POM fixture")
            try {
                check(fseek(file, 0, SEEK_END) == 0)
                val size = ftell(file).toInt(); rewind(file)
                val bytes = ByteArray(size)
                bytes.usePinned { check(fread(it.addressOf(0), 1u, size.toULong(), file) == size.toULong()) }
                return parser.parse(bytes)
            } finally { fclose(file) }
        }
        val sqlite = Coordinate("org.xerial", "sqlite-jdbc", "3.51.3.0")
        val junit = Coordinate("org.junit", "junit-bom", "5.12.2")
        val model = effectivePom(sqlite, fixture("org.xerial__sqlite-jdbc__3.51.3.0.pom")) {
            assertEquals(junit, it); fixture("org.junit__junit-bom__5.12.2.pom")
        }
        assertTrue(model.dependencies(runtime).isEmpty())
        assertEquals("5.12.2", model.management.getValue("org.junit.jupiter:junit-jupiter:jar:").getValue("version").text)
    }
}
