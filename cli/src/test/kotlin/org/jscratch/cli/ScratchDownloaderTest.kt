package org.jscratch.cli

import org.jscratch.compiler.Archives
import org.jscratch.compiler.convert.ProjectFormat
import org.jscratch.compiler.jvm.RhinoProjectJson
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScratchDownloaderTest {
    private val json = RhinoProjectJson()

    private val projectJson =
        """
        {"targets":[
          {"isStage":true,"name":"Stage","costumes":[{"name":"b","assetId":"aaaa","md5ext":"aaaa.svg","dataFormat":"svg"}],
           "sounds":[{"name":"pop","assetId":"bbbb","md5ext":"bbbb.wav","dataFormat":"wav"}]},
          {"isStage":false,"name":"Cat","costumes":[{"name":"c1","assetId":"aaaa","md5ext":"aaaa.svg","dataFormat":"svg"},
           {"name":"c2","assetId":"cccc","dataFormat":"png"}],"sounds":[]}
        ]}
        """.trimIndent()

    private val meta = """{"title":"My Game","author":{"username":"someone"},"project_token":"t0k3n"}"""

    @Test
    fun acceptsLinksAndIds() {
        assertEquals("123456", ScratchDownloader.projectId("123456"))
        assertEquals("123456", ScratchDownloader.projectId("https://scratch.mit.edu/projects/123456"))
        assertEquals("123456", ScratchDownloader.projectId("https://scratch.mit.edu/projects/123456/"))
        assertEquals("123456", ScratchDownloader.projectId("https://scratch.mit.edu/projects/123456/editor/"))
        assertEquals("123456", ScratchDownloader.projectId("scratch.mit.edu/projects/123456#comments"))
        assertEquals("123456", ScratchDownloader.projectId("http://www.scratch.mit.edu/projects/123456/fullscreen"))
        assertNull(ScratchDownloader.projectId("game.sb3"))
        assertNull(ScratchDownloader.projectId("https://scratch.mit.edu/users/someone"))
        assertNull(ScratchDownloader.projectId("https://example.com/projects/123456"))
    }

    @Test
    fun assetNamesAreUniqueAndFallBackToAssetId() {
        val names = ScratchDownloader.assetNames(json.parse(projectJson))
        assertEquals(listOf("aaaa.svg", "bbbb.wav", "cccc.png"), names.toList())
    }

    @Test
    fun downloadPacksProjectAndAssetsIntoAnSb3() {
        val requested = ArrayList<String>()
        val server =
            mapOf(
                "https://api.scratch.mit.edu/projects/42" to meta,
                "https://projects.scratch.mit.edu/42?token=t0k3n" to projectJson,
                "https://assets.scratch.mit.edu/internalapi/asset/aaaa.svg/get/" to "<svg/>",
                "https://assets.scratch.mit.edu/internalapi/asset/bbbb.wav/get/" to "RIFF",
            )
        val downloader =
            ScratchDownloader(json) { url ->
                synchronized(requested) { requested.add(url) }
                server[url]?.toByteArray()
            }
        val project = downloader.download("42")
        assertEquals(ProjectFormat.SB3, project.format)
        assertEquals("My Game", project.title)
        assertEquals("someone", project.author)
        assertEquals(listOf("asset cccc.png is missing from the Scratch server"), project.warnings)
        assertEquals(1, requested.count { it.endsWith("aaaa.svg/get/") }, "shared assets are fetched once")

        val entries = unzip(project.data)
        assertEquals(setOf("project.json", "aaaa.svg", "bbbb.wav"), entries.keys)
        assertContentEquals(projectJson.toByteArray(), entries["project.json"])
        assertEquals("<svg/>", entries["aaaa.svg"]!!.toString(Charsets.UTF_8))
    }

    @Test
    fun unsharedProjectsReportNotFound() {
        val e = assertFailsWith<DownloadException> { ScratchDownloader(json) { null }.download("7") }
        assertTrue(e.message!!.contains("not found"), e.message)
    }

    @Test
    fun scratch2ProjectsComeWithTheirAssets() {
        val sb2Json =
            """
            {"objName":"Stage","costumes":[{"costumeName":"b","baseLayerID":-1,"baseLayerMD5":"dddd.svg"}],
             "sounds":[{"soundName":"pop","soundID":-1,"md5":"eeee.wav"}],
             "children":[{"objName":"Cat","costumes":[{"costumeName":"c","baseLayerID":-1,"baseLayerMD5":"dddd.svg"},
               {"costumeName":"d","baseLayerID":-1,"baseLayerMD5":"ffff.png","textLayerMD5":"9999.png"}]},
              {"target":"Cat","cmd":"getVar:","param":"score"}]}
            """.trimIndent()
        val server =
            mapOf(
                "https://api.scratch.mit.edu/projects/9" to meta,
                "https://projects.scratch.mit.edu/9?token=t0k3n" to sb2Json,
                "https://assets.scratch.mit.edu/internalapi/asset/dddd.svg/get/" to "<svg/>",
                "https://assets.scratch.mit.edu/internalapi/asset/eeee.wav/get/" to "RIFF",
                "https://assets.scratch.mit.edu/internalapi/asset/ffff.png/get/" to "PNG",
                "https://assets.scratch.mit.edu/internalapi/asset/9999.png/get/" to "text",
            )
        val project = ScratchDownloader(json) { server[it]?.toByteArray() }.download("9")
        assertEquals(ProjectFormat.SB2, project.format)
        assertEquals(emptyList(), project.warnings)
        val entries = unzip(project.data)
        assertEquals(setOf("project.json", "dddd.svg", "ffff.png", "9999.png", "eeee.wav"), entries.keys)
        assertContentEquals(sb2Json.toByteArray(), entries["project.json"])
    }

    @Test
    fun jsonThatIsNoProjectIsRejected() {
        val server =
            mapOf(
                "https://api.scratch.mit.edu/projects/9" to meta,
                "https://projects.scratch.mit.edu/9?token=t0k3n" to """{"code":"NotFound","message":""}""",
            )
        val e =
            assertFailsWith<DownloadException> { ScratchDownloader(json) { server[it]?.toByteArray() }.download("9") }
        assertTrue(e.message!!.contains("not a Scratch project"), e.message)
    }

    @Test
    fun archivedScratch2ProjectsPassThrough() {
        val sb2 = Archives.writeProject("""{"objName":"Stage","children":[]}""".toByteArray(), emptyMap())
        val project =
            ScratchDownloader(json) { url ->
                if (url.startsWith("https://api.")) meta.toByteArray() else sb2
            }.download("9")
        assertEquals(ProjectFormat.SB2, project.format)
        assertContentEquals(sb2, project.data)
    }

    @Test
    fun scratch14ProjectsPassThrough() {
        val sb = "ScratchV02 and a Squeak object graph".toByteArray()
        val project =
            ScratchDownloader(json) { url ->
                if (url.startsWith("https://api.")) meta.toByteArray() else sb
            }.download("9")
        assertEquals(ProjectFormat.SB1, project.format)
        assertContentEquals(sb, project.data)
    }

    @Test
    fun archivedScratch3ProjectsPassThrough() {
        val sb3 = Archives.writeProject(projectJson.toByteArray(), mapOf("aaaa.svg" to "<svg/>".toByteArray()))
        val project =
            ScratchDownloader(json) { url ->
                if (url.startsWith("https://api.")) meta.toByteArray() else sb3
            }.download("9")
        assertEquals(ProjectFormat.SB3, project.format)
        assertContentEquals(sb3, project.data)
    }

    @Test
    fun aFailedAssetStopsTheOtherRequests() {
        val server =
            mapOf(
                "https://api.scratch.mit.edu/projects/42" to meta,
                "https://projects.scratch.mit.edu/42?token=t0k3n" to projectJson,
            )
        val downloader =
            ScratchDownloader(json) { url ->
                when {
                    url in server -> server[url]!!.toByteArray()
                    url.contains("bbbb.wav") -> throw DownloadException("asset server is down")
                    else -> {
                        Thread.sleep(60_000)
                        null
                    }
                }
            }
        val started = System.nanoTime()
        val e = assertFailsWith<DownloadException> { downloader.download("42") }
        assertEquals("asset server is down", e.message)
        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue(seconds < 10, "the slow requests should be interrupted, but the download took $seconds s")
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        return entries
    }
}
