package fixture
expect fun platformName(): String
fun decorated(): String = "hello ${platformName()}"
