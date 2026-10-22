package fixture.library
import fixture.hidden.hiddenValue
fun publicValue(): String = "library:" + hiddenValue()
