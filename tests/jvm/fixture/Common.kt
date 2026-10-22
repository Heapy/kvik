package fixture
expect fun platformName(): String
fun greeting(): String = "hello ${platformName()}"
