package dev.campusrun.core.config

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Offline tests for the Task 10 update channel (spec §5.6): `version.json` fetched from
 * an ordered mirror list with try-until-success fallback (first success wins, failures
 * logged not thrown), the `minApkVersionCode` gate that flags "new APK required", and the
 * repo's `config/version.json` sample as the schema's executable documentation. All
 * traffic stays on loopback MockWebServer — no live network.
 */
class UpdateCheckerTest {

    /** Captured mirror log lines: the checker must log failures instead of throwing. */
    private val logs = mutableListOf<String>()

    private val servers = mutableListOf<MockWebServer>()

    @AfterEach
    fun tearDown() {
        servers.forEach { server -> runCatching { server.shutdown() } }
    }

    @Test
    fun `first mirror fails second succeeds`() {
        val broken = mirror(MockResponse().setResponseCode(503))
        val healthy = mirror(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(SAMPLE_VERSION_JSON),
        )

        val info = UpdateChecker(log = logs::add).check(listOf(baseUrl(broken), baseUrl(healthy)))

        assertNotNull(info, "the second mirror must answer after the first one failed")
        assertEquals(3, info!!.minApkVersionCode)
        assertEquals(2, info.configVersion)
        assertEquals(5, info.protocolParamsVersion)
        assertEquals(
            listOf("https://mirror.invalid/a.apk", "https://mirror.invalid/b.apk"),
            info.apkMirrors,
        )
        assertEquals(1, broken.requestCount, "mirrors are tried in order, first one first")
        assertEquals(1, healthy.requestCount)
        assertTrue(logs.isNotEmpty(), "the failed mirror must be logged, not thrown")
    }

    @Test
    fun `every mirror failing yields null instead of an exception`() {
        val dead = mirror(MockResponse())
        dead.shutdown() // closed port: the transport-level mirror-down case (connection refused)
        val http = mirror(MockResponse().setResponseCode(500))

        val info = UpdateChecker(log = logs::add).check(listOf(baseUrl(dead), baseUrl(http)))

        assertNull(info, "no usable mirror means no update document, not a crash")
        assertTrue(logs.size >= 2, "every mirror failure is logged: $logs")
    }

    @Test
    fun `mirror serving an unparsable body falls through to the next`() {
        val garbage = mirror(MockResponse().setBody("<html>cdn error page</html>"))
        val healthy = mirror(MockResponse().setBody(SAMPLE_VERSION_JSON))

        val info = UpdateChecker(log = logs::add).check(listOf(baseUrl(garbage), baseUrl(healthy)))

        assertNotNull(info, "an unparsable answer counts as a failed mirror")
        assertEquals(3, info!!.minApkVersionCode)
        assertTrue(logs.isNotEmpty())
    }

    @Test
    fun `minApkVersionCode greater than current flags updateRequired`() {
        val info = RemoteVersionInfo(
            minApkVersionCode = 7,
            configVersion = 3,
            protocolParamsVersion = 5,
            apkMirrors = listOf("https://mirror.invalid/app.apk"),
        )
        val checker = UpdateChecker(log = logs::add)

        assertTrue(checker.updateRequired(info, currentApkVersionCode = 6), "an older APK must be prompted to update")
        assertFalse(checker.updateRequired(info, currentApkVersionCode = 7), "the current APK satisfies the floor")
        assertFalse(checker.updateRequired(info, currentApkVersionCode = 8), "a newer APK is always fine")
    }

    @Test
    fun `repo sample version json decodes through the real checker`() {
        val healthy = mirror(MockResponse().setBody(sampleVersionJson()))

        val info = UpdateChecker(log = logs::add).check(listOf(baseUrl(healthy)))

        assertNotNull(info, "config/version.json must parse through the production decoder")
        // Pinned sample values: the current build (versionCode 1) satisfies the floor,
        // and the sample's protocolParamsVersion matches the bundled versionTag (1).
        assertEquals(1, info!!.minApkVersionCode)
        assertEquals(1, info.configVersion)
        assertEquals(1, info.protocolParamsVersion)
        assertTrue(info.apkMirrors.isNotEmpty(), "the sample advertises download mirrors")
    }

    private fun mirror(response: MockResponse): MockWebServer =
        MockWebServer().also { server ->
            server.enqueue(response)
            server.start()
            servers += server
        }

    private fun baseUrl(server: MockWebServer): String = server.url("/").toString().trimEnd('/')

    /** Reads the committed sample from the repo (`config/version.json`, one level up when tests run from `core/`). */
    private fun sampleVersionJson(): String {
        val file = listOf(File("config/version.json"), File("../config/version.json"))
            .firstOrNull(File::isFile)
        assertNotNull(file, "config/version.json sample not found from ${File(".").absolutePath}")
        return file!!.readText()
    }

    private companion object {
        val SAMPLE_VERSION_JSON = """
            {"minApkVersionCode":3,"configVersion":2,"protocolParamsVersion":5,
             "apkMirrors":["https://mirror.invalid/a.apk","https://mirror.invalid/b.apk"]}
        """.trimIndent().replace("\n", "")
    }
}
