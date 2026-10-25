package fixture

fun label(name: String, count: Int): String = "${normalizeName(name)}:${count.coerceIn(0, 10)}"
