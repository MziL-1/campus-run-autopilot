package dev.campusrun.core.policy

import dev.campusrun.core.api.ApiClient
import dev.campusrun.core.config.ProtocolParams
import dev.campusrun.core.session.Token
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Offline parser tests over the live 7.4.x fixtures captured by Task 2
 * (`policy/run_mode_policy.json` is the decrypted runModePolicy business payload;
 * `policy/geo_fence.json` is the whole decrypted getGeoFenceForRun response).
 *
 * The school used for capture has no configured fence (`geoFences` is empty/null), so
 * fence/point geometry is covered by synthetic documents built inline; the live fixture
 * pins the rule values and the "empty is valid" path.
 */
class PolicyClientTest {

    @Test
    fun `live 7_4 fixture pins window pace and distance cap`() {
        val policy = parseRunPolicy(fixture("run_mode_policy.json"), fixture("geo_fence.json"))

        // runRuleModel.speedTop = 3.0 min/km, speedBottom = 10.0 min/km -> seconds per km.
        assertEquals(180, policy.paceMinSecPerKm)
        assertEquals(600, policy.paceMaxSecPerKm)
        // runRuleModel.validTime[0] = 10:00:00~22:00:00.
        assertEquals(10, policy.validFromHour)
        assertEquals(22, policy.validToHour)
        // runRuleModel.dayGoal = 2000.0 (the only distance ceiling the 7.4.x rule body has).
        assertEquals(2000, policy.maxDistancePerRunM)
        // No daily-count field in the 7.4.x rule body; weekCountGoal (0) is weekly, not daily.
        assertEquals(0, policy.dailyRunLimit)
        // runFaceCheckConfigDTO = null and the 7.4.x DTO carries no epoch window fields.
        assertNull(policy.faceWindow)
    }

    @Test
    fun `live 7_4 fixtures have no fence or points and parse as empty`() {
        val policy = parseRunPolicy(fixture("run_mode_policy.json"), fixture("geo_fence.json"))

        assertTrue(policy.fence.isEmpty(), "geoFences is empty for this school")
        assertTrue(policy.requiredPoints.isEmpty())
        assertTrue(policy.optionalPoints.isEmpty())
    }

    @Test
    fun `out of window document without runRuleModel is a valid empty policy`() {
        // 7.4.x returns only {policy, timestamp, message, ...} before 10:00 (findings §3.5).
        val empty = Json.parseToJsonElement(
            """{"policy":1,"timestamp":1794816298979,"message":null,""" +
                """"runAreaModels":null,"freezeRunTime":0,"runFaceCheckConfigDTO":null}""",
        )

        val policy = parseRunPolicy(empty, emptyFence())

        assertEquals(
            RunPolicy(
                requiredPoints = emptyList(),
                optionalPoints = emptyList(),
                fence = emptyList(),
                paceMinSecPerKm = 0,
                paceMaxSecPerKm = 0,
                validFromHour = 0,
                validToHour = 0,
                maxDistancePerRunM = 0,
                dailyRunLimit = 0,
                faceWindow = null,
            ),
            policy,
        )
    }

    @Test
    fun `empty runRuleModel object is a valid empty rule`() {
        val policy = parseRunPolicy(
            Json.parseToJsonElement("""{"policy":1,"runRuleModel":{}}"""),
            emptyFence(),
        )

        assertEquals(0, policy.paceMinSecPerKm)
        assertEquals(0, policy.maxDistancePerRunM)
    }

    @Test
    fun `malformed policy documents are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseRunPolicy(Json.parseToJsonElement("""[1,2,3]"""), emptyFence())
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseRunPolicy(Json.parseToJsonElement("""{"runRuleModel":"not-an-object"}"""), emptyFence())
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseRunPolicy(
                Json.parseToJsonElement("""{"runRuleModel":{"speedTop":"fast"}}"""),
                emptyFence(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseRunPolicy(
                Json.parseToJsonElement("""{"runRuleModel":{"validTime":"10:00:00~22:00:00"}}"""),
                emptyFence(),
            )
        }
    }

    @Test
    fun `fence business error or null geometry is empty not malformed`() {
        val businessError = Json.parseToJsonElement("""{"error":-10000,"message":"ERROR","data":null}""")
        val nullGeometry = Json.parseToJsonElement("""{"error":10000,"message":"成功","data":{"geoFences":null}}""")

        listOf(businessError, nullGeometry).forEach { fence ->
            val policy = parseRunPolicy(Json.parseToJsonElement("""{"policy":1}"""), fence)
            assertTrue(policy.fence.isEmpty(), "fence must stay empty for $fence")
            assertTrue(policy.requiredPoints.isEmpty())
            assertTrue(policy.optionalPoints.isEmpty())
        }
    }

    @Test
    fun `malformed fence geometry is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseRunPolicy(
                Json.parseToJsonElement("""{"policy":1}"""),
                Json.parseToJsonElement("""{"error":10000,"data":{"geoFences":{}}}"""),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseRunPolicy(
                Json.parseToJsonElement("""{"policy":1}"""),
                fenceWithPoints("""[{"lat":"north","lng":120.0}]"""),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseRunPolicy(
                Json.parseToJsonElement("""{"policy":1}"""),
                fenceWithPoints("""[{"lat":30.0}]"""),
            )
        }
    }

    @Test
    fun `synthetic fence yields polygon and required optional points`() {
        val policyDocument = Json.parseToJsonElement(
            """{"policy":1,"runRuleModel":{"dayGoal":3000,"minDistance":1500,""" +
                """"speedTop":4.5,"speedBottom":9.0,"weekCountGoal":4,""" +
                """"validTime":[{"start":"06:30:00","end":"20:15:00"}]}}""",
        )
        val fenceDocument = Json.parseToJsonElement(
            """
            {"error":10000,"message":"成功","data":{"geoFences":[{
              "id":3,"name":"synthetic",
              "points":[
                {"lat":30.0000,"lng":120.0000},
                {"lat":30.0000,"lng":120.0010},
                {"lat":30.0010,"lng":120.0010},
                {"lat":30.0010,"lng":120.0000}
              ],
              "passPoints":[
                {"lat":30.0010,"lon":120.0010,"pointName":"required-1","isFixed":1},
                {"lat":30.0020,"lon":120.0020,"pointName":"free-1","isFixed":0},
                {"lat":30.0030,"lon":120.0030,"pointName":"required-2"}
              ]
            }]}}
            """.trimIndent(),
        )

        val policy = parseRunPolicy(policyDocument, fenceDocument)

        assertEquals(270, policy.paceMinSecPerKm) // 4.5 min/km
        assertEquals(540, policy.paceMaxSecPerKm) // 9.0 min/km
        assertEquals(6, policy.validFromHour)
        assertEquals(20, policy.validToHour)
        assertEquals(3000, policy.maxDistancePerRunM) // dayGoal, not minDistance
        assertEquals(0, policy.dailyRunLimit) // weekCountGoal is weekly; no daily field exists

        assertEquals(4, policy.fence.size)
        assertEquals(LatLng(30.0000, 120.0000), policy.fence.first())
        assertEquals(LatLng(30.0010, 120.0000), policy.fence.last())

        assertEquals(2, policy.requiredPoints.size)
        assertEquals(LatLng(30.0010, 120.0010), policy.requiredPoints[0])
        assertEquals(LatLng(30.0030, 120.0030), policy.requiredPoints[1], "isFixed absent counts as required")

        assertEquals(1, policy.optionalPoints.size)
        assertEquals(LatLng(30.0020, 120.0020), policy.optionalPoints.single(), "lon is accepted as lng")
    }

    @Test
    fun `fetch posts policy then fence with session credentials`() {
        val paths = mutableListOf<String>()
        val api = apiServing(
            mapOf(
                "/api/v70103/runModePolicy" to fixtureText("run_mode_policy.json"),
                "/api/v1/getGeoFenceForRun" to fixtureText("geo_fence.json"),
            ),
            paths,
        )

        val policy = PolicyClient(api).fetch(Token("session-value", "uid-42", "35102", null), mode = 1)

        assertEquals(listOf("/api/v70103/runModePolicy", "/api/v1/getGeoFenceForRun"), paths)
        assertEquals("uid-42", api.uid)
        assertEquals("session-value", api.token)
        assertEquals(180, policy.paceMinSecPerKm)
        assertEquals(600, policy.paceMaxSecPerKm)
        assertEquals(10, policy.validFromHour)
        assertEquals(22, policy.validToHour)
    }

    @Test
    fun `fetch accepts the out of window empty document and indoor mode uses its own path`() {
        val paths = mutableListOf<String>()
        val api = apiServing(mapOf("/api/v59/indoorRunModePolicy" to "{}"), paths)

        val policy = PolicyClient(api).fetch(Token("session-value", "uid-42", "35102", null), mode = 5)

        assertEquals(listOf("/api/v59/indoorRunModePolicy", "/api/v1/getGeoFenceForRun"), paths)
        assertEquals(0, policy.paceMinSecPerKm)
        assertEquals(0, policy.validFromHour)
        assertTrue(policy.fence.isEmpty())
    }

    private fun emptyFence(): JsonElement = Json.parseToJsonElement(
        """{"error":10000,"message":"成功","data":{"updateTime":1561910400000,"geoFences":null}}""",
    )

    private fun fenceWithPoints(pointsJson: String): JsonElement = Json.parseToJsonElement(
        """{"error":10000,"message":"成功","data":{"geoFences":[{"id":1,"name":"s","points":$pointsJson}]}}""",
    )

    private fun fixture(name: String): JsonElement = Json.parseToJsonElement(fixtureText(name))

    private fun fixtureText(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream("policy/$name")) {
            "missing policy/$name fixture"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun apiServing(bodies: Map<String, String>, paths: MutableList<String>): ApiClient {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val path = chain.request().url.encodedPath
                paths += path
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body((bodies[path] ?: "{}").toResponseBody("application/json".toMediaType()))
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
}
