package kvik.core

import kotlin.test.*

class JvmTargetTest {
    @Test fun releaseSelectsTheSameTargetPolicyAsTheCompilerWithoutChangingDefaults() {
        assertEquals(JvmTargetSettings(11, null), jvmTargetSettings(emptyList()))
        assertEquals(JvmTargetSettings(17, null), jvmTargetSettings(listOf("-jvm-target", "17")))
        for (release in listOf(11, 17, 21, 25)) {
            for (args in listOf(listOf("-Xjdk-release=$release"), listOf("-Xjdk-release", "$release"),
                listOf("-jvm-target", "$release", "-Xjdk-release=$release"), listOf("-Xjdk-release=$release", "-jvm-target=$release"))) {
                assertEquals(JvmTargetSettings(release, release), jvmTargetSettings(args))
                assertEquals(release, jvmTargetFromOptions(args))
                assertEquals("$release", jvmVariantRequest("java-api", jvmTargetFromOptions(args)).attributes[JVM_TARGET_VERSION_ATTRIBUTE])
            }
        }
    }

    @Test fun unsupportedMissingRepeatedAndConflictingReleaseSettingsFail() {
        for (args in listOf(listOf("-Xjdk-release"), listOf("-Xjdk-release="), listOf("-Xjdk-release=1.8"),
            listOf("-Xjdk-release=26"), listOf("-Xjdk-release=011"), listOf("-Xjdk-release=11", "-Xjdk-release", "11"),
            listOf("-jvm-target=11", "-Xjdk-release=21"), listOf("-Xjdk-release=11", "-jvm-target=21"),
            listOf("-jvm-target=11", "-jvm-target=11"))) assertFails(args.toString()) { jvmTargetSettings(args) }
    }
}
