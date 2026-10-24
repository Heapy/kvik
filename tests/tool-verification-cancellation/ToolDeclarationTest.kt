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

    @Test fun optionalIosDeclarationsAndPlatformSettingsAreStrict() {
        val json = lock()
        val extra = mapOf("iosSdk" to Sha256.digest("device".encodeToByteArray()).toJson(),
            "iosSimulatorSdk" to Sha256.digest("simulator".encodeToByteArray()).toJson())
        val expanded = JsonValue.Object(json.values + ("components" to JsonValue.Object(json.values.getValue("components").objectValue() + extra)))
        if (nativeHost().first == "Darwin") {
            assertEquals(toolComponentsFor("Darwin", "arm64") + setOf(ToolComponent.IOS_SDK, ToolComponent.IOS_SIMULATOR_SDK),
                parseToolchainDeclaration(expanded.canonical()).components.keys)
        } else assertFails { parseToolchainDeclaration(expanded.canonical()) }
        fun settings(platform: String, version: String = "27.1") = obj("Version" to version.json(),
            "CanonicalName" to "$platform$version".json(), "DefaultProperties" to obj("PLATFORM_NAME" to platform.json()),
            "SupportedTargets" to obj(platform to obj())).canonical().encodeToByteArray()
        validateSdkSettings(ToolComponent.IOS_SDK, settings("iphoneos"), "unused")
        validateSdkSettings(ToolComponent.IOS_SIMULATOR_SDK, settings("iphonesimulator"), "unused")
        assertFails { validateSdkSettings(ToolComponent.IOS_SDK, settings("iphonesimulator"), "unused") }
        assertFails { validateSdkSettings(ToolComponent.IOS_SIMULATOR_SDK, settings("iphoneos"), "unused") }
        for (version in listOf("", "latest", "27.1\n")) {
            assertFails { validateSdkSettings(ToolComponent.IOS_SDK, settings("iphoneos", version), "unused") }
        }
        assertFails { validateSdkSettings(ToolComponent.IOS_SDK, obj("Version" to "27.1".json()).canonical().encodeToByteArray(), "unused") }
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
