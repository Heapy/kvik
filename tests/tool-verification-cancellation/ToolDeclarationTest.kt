package kvik.toolchains

import kotlin.test.*
import kvik.core.*

class ToolDeclarationTest {
    private fun lock(): JsonValue.Object {
        val host = nativeHost()
        return obj("schemaVersion" to CONTRACT_VERSION.json(), "kotlinVersion" to "2.4.20".json(),
            "host" to obj("os" to host.first.json(), "architecture" to host.second.json(),
                "release" to host.third.json(), "sdk" to (if (host.first == "Darwin") "fixture-sdk" else "").json()),
            "components" to JsonValue.Object(toolComponentsFor(host.first, host.second).associate {
                it.lockName to Sha256.digest(it.lockName.encodeToByteArray()).toJson()
            }))
    }

    @Test fun declarationValidatesDesiredIdentitiesWithoutAnyInstallationPaths() {
        val json = lock()
        val declaration = parseToolchainDeclaration(json.canonical())
        assertEquals("2.4.20", declaration.kotlinVersion)
        assertEquals(nativeHost().first, declaration.host.os)
        assertEquals(nativeHost().second, declaration.host.architecture)
        assertEquals(nativeHost().third, declaration.host.release)
        for ((component, digest) in declaration.components) {
            assertEquals(Sha256.digest(component.lockName.encodeToByteArray()), digest)
        }
    }

    @Test fun declarationRejectsWrongHostVersionShapeAndDigestInsteadOfAcceptingOpaquePins() {
        val json = lock()
        fun changed(name: String, value: JsonValue) = JsonValue.Object(json.values + (name to value)).canonical()
        assertFails { parseToolchainDeclaration(changed("schemaVersion", 2.json())) }
        assertFails { parseToolchainDeclaration(changed("kotlinVersion", "2.4.19".json())) }
        assertFails { parseToolchainDeclaration(changed("unexpected", true.json())) }
        val host = json.values.getValue("host").objectValue()
        for ((key, value) in listOf("os" to "Other", "architecture" to "other", "release" to "other")) {
            assertFails { parseToolchainDeclaration(changed("host", JsonValue.Object(host + (key to value.json())))) }
        }
        assertFails { parseToolchainDeclaration(changed("host", JsonValue.Object(host + ("extra" to "value".json())))) }
        val sdk = if (nativeHost().first == "Darwin") "" else "AppleSDK"
        assertFails { parseToolchainDeclaration(changed("host", JsonValue.Object(host + ("sdk" to sdk.json())))) }
        val components = json.values.getValue("components").objectValue()
        assertFails { parseToolchainDeclaration(changed("components", JsonValue.Object(components - "jdk"))) }
        assertFails { parseToolchainDeclaration(changed("components", JsonValue.Object(components + ("extra" to components.getValue("jdk"))))) }
        for (digest in listOf(obj("hash" to "invalid".json(), "sizeBytes" to 1.json()),
            obj("hash" to "0".repeat(64).json(), "sizeBytes" to (-1).json()))) {
            assertFails { parseToolchainDeclaration(changed("components", JsonValue.Object(components + ("jdk" to digest)))) }
        }
        assertFails { parseToolchainDeclaration(json.canonical() + " ".repeat(1048576)) }
    }
}
