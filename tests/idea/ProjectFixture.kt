package kvik.cli

/** Emits the production XML projection for the real IDEA import acceptance harness. */
fun main(args: Array<String>) {
    require(args.size in 2..3)
    val root = args[0]
    val library = IdeaLibrary("org.jetbrains.kotlin:kotlin-stdlib:2.4.20", args[1])
    fun compilation(name: String, directory: String, dependencies: List<String> = emptyList(), friend: String? = null) =
        IdeaCompilation(name, listOf(IdeaRoot("$root/$directory")), dependencies, listOf(library),
            if (directory == "tests") 21 else 17, emptyList(), friend, test = directory == "tests")
    val modules = listOf(
        compilation("hidden.main", "hidden"),
        compilation("base.main", "base", listOf("hidden.main")),
        compilation("app.main", "app", listOf("base.main")),
        compilation("app.test", "tests", listOf("app.main", "base.main"), "app.main".takeIf { args.getOrNull(2) != "without-friend" }),
    )
    val files = ideaProjectFiles("kvik-probe", modules, listOf(
        IdeaRun("Kvik quoted command", listOf("/usr/bin/printf", "%s", "quote' dollar\$ backtick` space & < >"), root),
    ))
    for ((path, contents) in files) println("$path\t${contents.encodeToByteArray().toHexString()}")
}
