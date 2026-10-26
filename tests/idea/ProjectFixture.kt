package kvik.cli

/** Emits the production XML projection for the real IDEA import acceptance harness. */
fun main(args: Array<String>) {
    verifyProjectionContracts()
    require(args.size in 2..4)
    val root = args[0]
    val library = IdeaLibrary("org.jetbrains.kotlin:kotlin-stdlib:2.4.20", args[1])
    fun compilation(name: String, directory: String, dependencies: List<String> = emptyList(), friend: String? = null) =
        IdeaCompilation(name, listOf(IdeaRoot("$root/$directory")), dependencies, listOf(library),
            IdeaPlatform.Jvm(if (directory == "tests") 21 else 17), emptyList(), friend, test = directory == "tests")
    val common = args.getOrNull(3)?.let { path ->
        val metadata = IdeaLibrary("kotlin-stdlib-common", path, common = true)
        val platform = IdeaPlatform.Common(setOf("jvm", "macosArm64"))
        listOf(
            IdeaCompilation("common.root", listOf(IdeaRoot("$root/common")), emptyList(), listOf(metadata), platform, emptyList()),
            IdeaCompilation("common.shared", listOf(IdeaRoot("$root/shared")), emptyList(), listOf(metadata), platform, emptyList(),
                refines = listOf("common.root")),
        )
    }.orEmpty()
    val modules = listOf(
        compilation("hidden.main", "hidden"),
        compilation("base.main", "base", listOf("hidden.main")),
        compilation("app.main", "app", listOf("base.main") + if (common.isNotEmpty()) listOf("common.shared", "common.root") else emptyList()).copy(
            refines = if (common.isNotEmpty() && args.getOrNull(2) != "without-refinement") listOf("common.shared") else emptyList()),
        compilation("app.test", "tests", listOf("app.main", "base.main"), "app.main".takeIf { args.getOrNull(2) != "without-friend" }),
    ) + common
    val files = ideaProjectFiles("kvik-probe", modules, listOf(
        IdeaRun("Kvik quoted command", listOf("/usr/bin/printf", "%s", "quote' dollar\$ backtick` space & < >"), root),
    ))
    for ((path, contents) in files) println("$path\t${contents.encodeToByteArray().toHexString()}")
}

/** Reject models that would silently grant platform or refinement visibility in the IDE. */
private fun verifyProjectionContracts() {
    val common = IdeaCompilation("common", emptyList(), emptyList(), emptyList(),
        IdeaPlatform.Common(setOf("jvm", "macosArm64")), emptyList())
    val jvm = IdeaCompilation("jvm", emptyList(), emptyList(), emptyList(), IdeaPlatform.Jvm(17), emptyList(), refines = listOf("common"))
    fun rejected(modules: List<IdeaCompilation>) {
        var rejected = false
        try { ideaProjectFiles("probe", modules, emptyList()) } catch (_: IllegalArgumentException) { rejected = true }
        check(rejected) { "Accepted an invalid IDE model" }
    }
    rejected(listOf(common.copy(refines = listOf("common"))))
    rejected(listOf(common.copy(refines = listOf("other")), common.copy(name = "other", refines = listOf("common"))))
    rejected(listOf(common.copy(platform = IdeaPlatform.Common(setOf("js", "macosArm64"))), jvm))
    rejected(listOf(common, jvm.copy(refines = listOf("other")), jvm.copy(name = "other", refines = emptyList())))
    rejected(listOf(common.copy(libraries = listOf(IdeaLibrary("jvm", "/stdlib.jar")))))
    rejected(listOf(jvm.copy(refines = emptyList(), libraries = listOf(IdeaLibrary("metadata", "/stdlib.klib", common = true)))))
    rejected(listOf(common.copy(modules = listOf("jvm")), jvm.copy(refines = emptyList())))
}
