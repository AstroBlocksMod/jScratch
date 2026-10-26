package org.jscratch.cli

import org.jscratch.compiler.Archives
import org.jscratch.compiler.convert.ProjectFormat
import org.jscratch.compiler.json.JsonValue
import org.jscratch.compiler.json.ProjectJson
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

class DownloadException(message: String) : RuntimeException(message)

/**
 * A project fetched from the Scratch website, as the file its Scratch version saves: an .sb3
 * or .sb2 archive, or a Scratch 1.4 .sb file.
 */
class DownloadedProject(
    val id: String,
    val title: String,
    val author: String,
    val format: ProjectFormat,
    val data: ByteArray,
    val warnings: List<String>,
)

/**
 * Downloads shared projects from scratch.mit.edu the way the Scratch player does:
 * the project API hands out a short-lived token, the project server returns
 * `project.json` for that token, and every costume and sound comes from the asset
 * server by its MD5. The pieces are zipped into an ordinary .sb3.
 *
 * Projects last saved by older editors come back as they were stored. A Scratch 1.4
 * project is its .sb file, and one uploaded from the Scratch 2 offline editor is its
 * .sb2 archive. A project saved by the online Scratch 2 editor is Scratch 2
 * `project.json`, and is zipped with its assets named by MD5, the way the converter
 * looks them up.
 *
 * [fetch] does the HTTP GET and returns null for a 404. It is a parameter so the
 * tests can run without a network.
 */
class ScratchDownloader(private val json: ProjectJson, private val fetch: (String) -> ByteArray? = ::httpGet) {
    fun download(id: String): DownloadedProject {
        val meta =
            fetch("$API/projects/$id")
                ?: throw DownloadException("project $id was not found. Only shared projects can be downloaded.")
        val info = parseJson(meta, "the project API")
        val title = info["title"]?.asString ?: id
        val author = info["author"]?.get("username")?.asString ?: "unknown"
        val token = info["project_token"]?.asString
        val projectUrl = if (token != null) "$PROJECTS/$id?token=$token" else "$PROJECTS/$id"
        val projectBytes =
            fetch(projectUrl) ?: throw DownloadException("the project server has no data for project $id")

        fun project(format: ProjectFormat, data: ByteArray, warnings: List<String> = emptyList()) =
            DownloadedProject(id, title, author, format, data, warnings)

        if (projectBytes.startsWith("ScratchV0")) return project(ProjectFormat.SB1, projectBytes)
        // Projects uploaded from the Scratch 2 offline editor are stored as a whole archive.
        if (Archives.isZip(projectBytes)) {
            val archived =
                Archives.read(projectBytes)["project.json"]
                    ?: throw DownloadException("the archive for project $id has no project.json")
            return project(format(parseJson(archived, "the project archive")), projectBytes)
        }

        val project = parseJson(projectBytes, "the project server")
        val format = format(project)
        val names = if (format == ProjectFormat.SB3) assetNames(project) else sb2AssetNames(project)
        val warnings = ArrayList<String>()
        val assets = fetchAssets(names, warnings)
        return project(format, Archives.writeProject(projectBytes, assets), warnings)
    }

    /** Scratch 3 keeps its sprites in "targets"; Scratch 2 nests them under the stage. */
    private fun format(project: JsonValue) =
        if (project["targets"]?.asArray != null) ProjectFormat.SB3 else ProjectFormat.SB2

    private fun parseJson(bytes: ByteArray, source: String): JsonValue = try {
        json.parse(bytes.toString(Charsets.UTF_8))
    } catch (e: Exception) {
        throw DownloadException("$source returned something that is not JSON: ${e.message}")
    }

    /**
     * Fetches assets in parallel on virtual threads, a few at a time to stay polite to
     * the asset server. Results are taken in completion order, so the first failure
     * stops the download right away instead of after every queued request.
     */
    private fun fetchAssets(names: Collection<String>, warnings: MutableList<String>): Map<String, ByteArray> {
        val permits = Semaphore(PARALLEL_ASSETS)
        val fetched = HashMap<String, ByteArray?>()
        Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            val done = ExecutorCompletionService<Pair<String, ByteArray?>>(pool)
            for (name in names) {
                done.submit {
                    permits.acquire()
                    try {
                        name to fetch("$ASSETS/$name/get/")
                    } finally {
                        permits.release()
                    }
                }
            }
            try {
                repeat(names.size) { fetched += done.take().get() }
            } catch (e: ExecutionException) {
                // Interrupt the remaining requests; otherwise close() waits for all of them.
                pool.shutdownNow()
                throw e.cause ?: e
            }
        }
        val assets = LinkedHashMap<String, ByteArray>()
        for (name in names) {
            val data = fetched[name]
            if (data != null) assets[name] = data else warnings.add("asset $name is missing from the Scratch server")
        }
        return assets
    }

    companion object {
        private const val API = "https://api.scratch.mit.edu"
        private const val PROJECTS = "https://projects.scratch.mit.edu"
        private const val ASSETS = "https://assets.scratch.mit.edu/internalapi/asset"
        private const val PARALLEL_ASSETS = 16

        private val PROJECT_URL =
            Regex("""(?:https?://)?(?:www\.)?scratch\.mit\.edu/projects/(\d+)(?:[/?#].*)?""", RegexOption.IGNORE_CASE)
        private val PROJECT_ID = Regex("""\d+""")

        private val client: HttpClient by lazy {
            HttpClient
                .newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build()
        }

        /**
         * The project ID in a scratch.mit.edu project link (`/projects/<id>`, with or
         * without `/editor` or `/fullscreen`) or a bare numeric ID, or null.
         */
        fun projectId(arg: String): String? {
            val s = arg.trim()
            if (PROJECT_ID.matches(s)) return s
            return PROJECT_URL.matchEntire(s)?.groupValues?.get(1)
        }

        /** The file names of every costume and sound in a Scratch 3 `project.json`, without duplicates. */
        fun assetNames(project: JsonValue): Set<String> {
            val names = LinkedHashSet<String>()
            for (target in project["targets"]?.asArray?.items ?: emptyList()) {
                for (key in listOf("costumes", "sounds")) {
                    for (asset in target[key]?.asArray?.items ?: emptyList()) {
                        val md5ext =
                            asset["md5ext"]?.asString
                                ?: asset["assetId"]?.asString?.let { id ->
                                    asset["dataFormat"]?.asString?.let { "$id.$it" }
                                }
                        if (md5ext != null) names.add(md5ext)
                    }
                }
            }
            return names
        }

        /**
         * The file names of every costume image and sound in a Scratch 2 `project.json`, the
         * stage's and those of every sprite under it, without duplicates. Scratch 2 names them
         * by MD5.
         */
        fun sb2AssetNames(project: JsonValue): Set<String> {
            val names = LinkedHashSet<String>()

            fun visit(obj: JsonValue) {
                for (costume in obj["costumes"]?.asArray?.items ?: emptyList()) {
                    costume["baseLayerMD5"]?.asString?.let { names.add(it) }
                    // Text from Scratch 1.4, kept as a second image and merged by the converter.
                    costume["textLayerMD5"]?.asString?.let { names.add(it) }
                }
                for (sound in obj["sounds"]?.asArray?.items ?: emptyList()) {
                    sound["md5"]?.asString?.let { names.add(it) }
                }
                for (child in obj["children"]?.asArray?.items ?: emptyList()) visit(child)
            }
            visit(project)
            return names
        }

        private fun httpGet(url: String): ByteArray? {
            val request =
                HttpRequest
                    .newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("User-Agent", "jScratch (https://github.com/AstroBlocksMod/jScratch)")
                    .GET()
                    .build()
            val response =
                try {
                    client.send(request, HttpResponse.BodyHandlers.ofByteArray())
                } catch (e: IOException) {
                    throw DownloadException(
                        "could not reach ${request.uri().host}: ${e.message ?: e.javaClass.simpleName}",
                    )
                }
            return when (response.statusCode()) {
                200 -> response.body()
                404 -> null
                // The path only: the query holds the project token.
                else -> throw DownloadException(
                    "${request.uri().host} answered HTTP ${response.statusCode()} for ${request.uri().path}",
                )
            }
        }

        private fun ByteArray.startsWith(prefix: String): Boolean =
            size >= prefix.length && prefix.indices.all { this[it] == prefix[it].code.toByte() }
    }
}
