package kvik.experimental.effects

/** Research contract only: compiler acceptance does not grant cache eligibility. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class Task(
    val environment: Array<String> = [],
    val standardOutput: Boolean = false,
    val clock: Boolean = false,
)
