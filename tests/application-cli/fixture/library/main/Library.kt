package fixture

fun libraryValue(): String = support.runtimeValue()
fun literalArguments(args: Array<String>): String = args.joinToString("|") { value ->
    value.encodeToByteArray().joinToString("") { byte -> (byte.toInt() and 255).toString(16).padStart(2, '0') }
}
