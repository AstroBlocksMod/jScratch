package org.jscratch.cli

import org.jscratch.compiler.json.JsonValue
import org.jscratch.compiler.json.ProjectJson
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DownloadException(message: String) : RuntimeException(message)

/** A project fetched from the Scratch website, packed into an .sb3 archive. */
class DownloadedProject(
    val id: String,
    val title: String,
    val author: String,
    val sb3: ByteArray,
    val warnings: List<String>,
)

/**
 * Downloads shared projects from scratch.mit.edu the way the Scratch player does:
 * the project API hands out a short-lived token, the project server returns
 * `project.json` for that token, and every costume and sound comes from the asset
 * server by its `md5ext`. The pieces are zipped into an ordinary .sb3.
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

        if (projectBytes.startsWith("ScratchV0")) {
            throw DownloadException("project $id is a Scratch 1.4 project. jScratch only compiles Scratch 3 projects.")
        }
        // A few projects are stored as a whole archive instead of bare project.json.
        if (projectBytes.startsWith("PK")) return DownloadedProject(id, title, author, projectBytes, emptyList())

        val project = parseJson(projectBytes, "the project server")
        if (project["targets"]?.asArray == null) {
            throw DownloadException(
                "project $id is a Scratch 2 project. jScratch only compiles Scratch 3 projects; " +
                    "opening it in the Scratch editor and saving it again converts it.",
            )
        }
        val warnings = ArrayList<String>()
        val assets = fetchAssets(assetNames(project), warnings)
        return DownloadedProject(id, title, author, zip(projectBytes, assets), warnings)
    }

    private fun parseJson(bytes: ByteArray, source: String): JsonValue = try {
        json.parse(bytes.toString(Charsets.UTF_8))
    } catch (e: Exception) {
        throw DownloadException("$source returned something that is not JSON: ${e.message}")
    }

    /** Fetches assets in parallel on virtual threads, a few at a time to stay polite to the asset server. */
    private fun fetchAssets(names: Collection<String>, warnings: MutableList<String>): Map<String, ByteArray> {
        val permits = Semaphore(PARALLEL_ASSETS)
        val results =
            Executors.newVirtualThreadPerTaskExecutor().use { pool ->
                names
                    .map { name ->
                        name to
                            pool.submit<ByteArray?> {
                                permits.acquire()
                                try {
                                    fetch("$ASSETS/$name/get/")
                                } finally {
                                    permits.release()
                                }
                            }
                    }.map { (name, future) ->
                        try {
                            name to future.get()
                        } catch (e: ExecutionException) {
                            throw e.cause ?: e
                        }
                    }
            }
        val assets = LinkedHashMap<String, ByteArray>()
        for ((name, data) in results) {
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

        fun zip(projectJson: ByteArray, assets: Map<String, ByteArray>): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("project.json"))
                zip.write(projectJson)
                zip.closeEntry()
                for ((name, data) in assets) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(data)
                    zip.closeEntry()
                }
            }
            return out.toByteArray()
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
