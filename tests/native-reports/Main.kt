package kvik.reportfixture

import audit.AuditProjectConfig
import audit.LifecycleSpec
import io.kotest.core.spec.SpecRef
import kotlinx.coroutines.runBlocking

fun main() {
    runBlocking {
        kvik.kotest.runtime.invokeTestEngine(listOf(SpecRef.Function({ LifecycleSpec() }, LifecycleSpec::class, "audit.LifecycleSpec")), AuditProjectConfig())
    }
    kvik.kotest.runtime.main()
}
