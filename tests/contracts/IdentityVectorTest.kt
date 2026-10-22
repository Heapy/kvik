package kvik.contracts

import kvik.core.*
import kotlin.test.*

// Generated from independent Python canonicalization, checked by Kotlin/Native.
class IdentityVectorTest {
    @Test fun actionIdentity() {
        val canonical = "{\"arguments\":[\"-jvm-target\",\"21\"],\"cachePolicy\":\"local\",\"composition\":{\"hash\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\",\"sizeBytes\":0},\"environment\":{\"LANG\":\"C\"},\"implementation\":\"kvik.jvm-compile.v1\",\"inputs\":{\"files\":[{\"digest\":{\"hash\":\"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824\",\"sizeBytes\":5},\"executable\":false,\"path\":\"src/Hello.kt\"}],\"schemaVersion\":1},\"kind\":\"compile\",\"outputs\":[\"classes\"],\"parameters\":{},\"platform\":{\"arch\":\"arm64\",\"os\":\"macos\"},\"schemaVersion\":1,\"target\":\"jvm\",\"timeoutMillis\":120000,\"toolchains\":{\"compiler\":{\"hash\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\",\"sizeBytes\":0}}}"
        assertEquals(canonical, parseJson(canonical).canonical())
        assertEquals(Digest("9c917f50d8db74a23e5398444b0575c6b2f2bd4605b7d45690a3c86c788049fc", 675), Sha256.digest(parseJson(canonical)))
    }
    @Test fun treeIdentity() {
        val canonical = "{\"files\":[{\"digest\":{\"hash\":\"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824\",\"sizeBytes\":5},\"executable\":false,\"path\":\"src/Hello.kt\"}],\"schemaVersion\":1}"
        assertEquals(canonical, parseJson(canonical).canonical())
        assertEquals(Digest("8b09fce3d4d67d7f952bf327b0330bb879227a8328827bdf033b254ed989ba5d", 171), Sha256.digest(parseJson(canonical)))
    }
    @Test fun runtimeLockIdentity() {
        val canonical = "{\"arguments\":[\"-target\",\"macos_arm64\"],\"environment\":{},\"libraries\":[],\"platform\":{\"arch\":\"arm64\",\"os\":\"macos\",\"sdk\":\"27.0\"},\"pluginApiVersion\":1,\"recipe\":{\"hash\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\",\"sizeBytes\":0},\"registry\":[\"example.ProbePlugin\"],\"schemaVersion\":1,\"sources\":[{\"id\":\"kvik\",\"kind\":\"local\",\"origin\":\".\",\"revision\":null,\"tree\":{\"hash\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\",\"sizeBytes\":0}}],\"toolchains\":{\"kotlinNative\":{\"hash\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\",\"sizeBytes\":0}}}"
        assertEquals(canonical, parseJson(canonical).canonical())
        assertEquals(Digest("09f3812a9ccd9f99c16b3bd1227048d430d78bf6e0187fcc7b77bb667bfcd380", 585), Sha256.digest(parseJson(canonical)))
    }
}
