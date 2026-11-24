package dev.campusrun.core.campus

import dev.campusrun.core.api.ApiClient
import dev.campusrun.core.config.ProtocolParams
import dev.campusrun.core.session.Token
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Offline tests for the campus-list leg (`GET /api/v61101/campus/zonelist?unid=…`).
 *
 * The payload is a **synthetic** envelope with the upstream entity's field set
 * (`MyCampusBean`: `allowChange`, `isSelected`, `name`, `unid` — recovered from the
 * 7.4.0 FridaDump DEX) and dummy campus names: no live capture exists for this
 * endpoint, and today's account session must not be disturbed by an extra login, so the
 * shape is pinned from upstream + DEX instead of the wire (see the campus-switcher
 * report for that disclosure).
 */
class CampusClientTest {

    @Test
    fun `campus list fixture parses every upstream MyCampusBean field`() {
        val campuses = parseCampusList(Json.parseToJsonElement(CAMPUS_LIST))

        assertEquals(2, campuses!!.size)
        assertEquals(CampusOption(35102, "示例校区A", allowChange = true, isSelected = true), campuses[0])
        assertEquals(CampusOption(35103, "示例校区B", allowChange = true, isSelected = false), campuses[1])
        assertFalse(campuses[1].isSelected, "only the platform's own campus is marked selected")
    }

    @Test
    fun `business error or missing data reads as no answer not as an empty list`() {
        assertNull(parseCampusList(Json.parseToJsonElement("""{"error":50006,"message":"失败","data":[]}""")))
        assertNull(parseCampusList(Json.parseToJsonElement("""{"error":10000,"message":"成功"}""")))
        assertNull(parseCampusList(Json.parseToJsonElement("""{"error":10000,"message":"成功","data":null}""")))
    }

    @Test
    fun `structurally wrong documents throw instead of guessing`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseCampusList(Json.parseToJsonElement("[]"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseCampusList(Json.parseToJsonElement("""{"error":10000,"data":{"unid":1}}"""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseCampusList(Json.parseToJsonElement("""{"error":10000,"data":[[]}"""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseCampusList(Json.parseToJsonElement("""{"error":10000,"data":[{"name":"示例校区A"}]}"""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseCampusList(Json.parseToJsonElement("""{"error":10000,"data":[{"unid":1}]}"""))
        }
    }

    @Test
    fun `fetch requests the upstream zonelist path with the session unid and installs credentials`() {
        val paths = mutableListOf<String>()
        val queries = mutableListOf<String?>()
        val api = apiServing(CAMPUS_LIST, paths, queries)

        val campuses = CampusClient(api).fetch(TOKEN)

        assertEquals(listOf("/api/v61101/campus/zonelist"), paths, "upstream getMyCampusList path")
        assertEquals(listOf("unid=35102"), queries, "the @Query(\"unid\") parameter is the session unid")
        assertEquals("uid-42", api.uid, "the leg must install the session identity like the other clients")
        assertEquals("session-value", api.token)
        assertEquals(2, campuses!!.size)
        assertTrue(campuses.all { it.unid > 0 }, "every campus carries its unid")
    }

    private fun apiServing(body: String, paths: MutableList<String>, queries: MutableList<String?>): ApiClient {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                paths += request.url.encodedPath
                queries += request.url.query
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        return ApiClient(params = protocolParams(), client = client)
    }

    /** Reuses the Task 5 gmt fixtures for a valid NetSecKit parameter set (no network). */
    private fun protocolParams(): ProtocolParams {
        val text = checkNotNull(javaClass.classLoader.getResourceAsStream("session/gmttime.json")) {
            "missing session/gmttime.json"
        }.use { it.readBytes().toString(Charsets.UTF_8) }
        val case = Json.parseToJsonElement(text).jsonObject.getValue("cases").jsonArray.first().jsonObject
        return ProtocolParams(
            aesKey = case.string("aesKeyHex").hexToBytes(),
            kdfSalt = case.string("kdfSaltHex").hexToBytes(),
            rsaPublicKey = case.string("rsaPublicKey"),
            endpointBase = "https://offline.invalid",
            signSalt = "2slhe02lsfiwowlcixisla_sls-_slaor",
            versionTag = 1,
        )
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {
        val TOKEN = Token(value = "session-value", uid = "uid-42", unid = "35102", expiresAtMs = null)

        /** Synthetic BasicBean<List<MyCampusBean>> with dummy campus names (no live PII). */
        const val CAMPUS_LIST =
            """{"error":10000,"message":"成功","data":[""" +
                """{"unid":35102,"name":"示例校区A","allowChange":true,"isSelected":true},""" +
                """{"unid":35103,"name":"示例校区B","allowChange":true,"isSelected":false}]}"""
    }
}
