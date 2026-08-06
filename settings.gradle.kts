apply(from = "repositories.gradle.kts")

include(":core")

// Load all modules under /lib
File(rootDir, "lib").eachDir { include("lib:${it.name}") }

// Load all modules under /lib-multisrc
File(rootDir, "lib-multisrc").eachDir { include("lib-multisrc:${it.name}") }

val ciChunkSize = System.getenv("CI_CHUNK_SIZE")
val ciChunkNum = System.getenv("CI_CHUNK_NUM")

if (System.getenv("CI") != "true" || ciChunkSize == null || ciChunkNum == null) {
    // Local development or simple CI (full project build)
    loadAllIndividualExtensions()
} else {
    // Running in chunked CI
    val chunkSize = ciChunkSize.toInt()
    val chunk = ciChunkNum.toInt()

    File(rootDir, "src").getChunk(chunk, chunkSize)?.forEach {
        loadIndividualExtension(it.parentFile.name, it.name)
    }
}

fun loadAllIndividualExtensions() {
    File(rootDir, "src").eachDir { dir ->
        dir.eachDir { subdir ->
            loadIndividualExtension(dir.name, subdir.name)
        }
    }
}

fun loadIndividualExtension(lang: String, name: String) {
    include("src:$lang:$name")
}

fun File.getChunk(chunk: Int, chunkSize: Int): List<File>? {
    return listFiles()
        ?.filter { it.isDirectory }
        ?.mapNotNull { dir -> dir.listFiles()?.filter { it.isDirectory } }
        ?.flatten()
        ?.sortedBy { it.name }
        ?.chunked(chunkSize)
        ?.get(chunk)
}

fun File.eachDir(block: (File) -> Unit) {
    val files = listFiles() ?: return
    for (file in files) {
        if (file.isDirectory && file.name != ".gradle" && file.name != "build") {
            block(file)
        }
    }
}

gradle.rootProject {
    tasks.register("assembleRelease") {
        dependsOn(subprojects.map { sub -> sub.tasks.matching { it.name == "assembleDebug" } })
    }
}