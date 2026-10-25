package fixture

import io.kotest.core.spec.style.FunSpec
import io.kotest.core.spec.style.DescribeSpec
import org.junit.jupiter.api.Test

class RecoverSpec : FunSpec({
    test("flaky") { if (++attempts == 1) throw AssertionError("original failure") }
    test("sibling") { check(true) }
    xtest("ignored") { error("must not execute") }
}) { companion object { var attempts = 0 } }

class AlwaysFailSpec : FunSpec({ test("flaky") { throw AssertionError("original failure") } })

class NestedSpec : DescribeSpec({
    describe("nested") {
        it("flaky") { if (++attempts == 1) throw AssertionError("original failure") }
        it("sibling") { check(true) }
    }
}) { companion object { var attempts = 0 } }

class LifecycleSpec : FunSpec({
    afterSpec { error("lifecycle failed") }
    test("flaky") { throw AssertionError("original failure") }
})

class DisappearingSpec : FunSpec({
    if (attempts == 0) test("flaky") { attempts++; throw AssertionError("original failure") }
    test("sibling") { check(true) }
}) { companion object { var attempts = 0 } }

class ExpandingSpec : FunSpec({
    if (attempts > 0) test("new sibling") { check(true) }
    test("flaky") { if (++attempts == 1) throw AssertionError("original failure") }
}) { companion object { var attempts = 0 } }

class JupiterSibling { @Test fun sibling() { check(true) } }
