package dev.campusrun.core.policy

import dev.campusrun.core.api.ApiClient
import dev.campusrun.core.config.ProtocolParams
import dev.campusrun.core.crypto.NetSecKit
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
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * Offline tests over the live 7.4.x fixtures captured by Task 2 and the production
 * envelope shape:
 *
 * - `policy/run_mode_policy.json` is the **rule body** (the decrypted `data` of
 *   runModePolicy), which is what `fetch` hands to `parseRunPolicy` after unwrapping.
 * - `policy/geo_fence.json` is the **whole** decrypted getGeoFenceForRun response
 *   (`{error, message, data}`), which the parser consumes as-is.
 * - The `fetch` tests serve the **wrapped** runModePolicy envelope, because that is the
 *   depth `ApiClient.decode` returns in production (upstream `core/client.py`).
 *
 * The school used for capture has no configured fence (`geoFences` is empty/null), so
 * fence/point geometry is covered by synthetic documents built inline; the live fixture
 * pins the rule values and the "empty is valid" path.
 */
class PolicyClientTest {

    @Test
    fun `rule body fixture pins window pace and distance cap`() {
        val policy = parseRunPolicy(fixture("run_mode_policy.json"), fixture("geo_fence.json"))

        // runRuleModel.speedTop = 3.0 min/km, speedBottom = 10.0 min/km -> seconds per km.
        assertEquals(180, policy.paceMinSecPerKm)
        assertEquals(600, policy.paceMaxSecPerKm)
        // runRuleModel.validTime[0] = 10:00:00~22:00:00.
        assertEquals(10, policy.validFromHour)
        assertEquals(22, policy.validToHour)
        // runRuleModel.dayGoal = 2000.0 (the only distance ceiling the 7.4.x rule body has).
        assertEquals(2000, policy.maxDistancePerRunM)
        // runRuleModel.minDistance = 2000.0 — the per-run floor (Task 9 gate input).
        assertEquals(2000, policy.minDistancePerRunM)
        // No daily-count field in the 7.4.x rule body; weekCountGoal (0) is weekly, not daily.
        assertEquals(0, policy.dailyRunLimit)
        // The live fixture advertises weekCountGoal=0 (not set for this school).
        assertEquals(0, policy.weekCountGoal)
        // runFaceCheckConfigDTO = null and the 7.4.x DTO carries no epoch window fields.
        assertNull(policy.faceWindow)
    }

    @Test
    fun `rule body fixture has no fence or points and parses as empty`() {
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
        assertEquals(1500, policy.minDistancePerRunM) // minDistance is the per-run floor
        assertEquals(0, policy.dailyRunLimit) // weekCountGoal is weekly; no daily field exists
        // weekCountGoal (4) must be surfaced as its own field, not folded into dailyRunLimit.
        assertEquals(4, policy.weekCountGoal)

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
    fun `fetch posts policy then fence then five-point with session credentials`() {
        val paths = mutableListOf<String>()
        val requestBodies = mutableMapOf<String, String>()
        val api = apiServing(
            mapOf(
                // Production depth: ApiClient.decode returns the envelope, so fetch must
                // unwrap `{error, message, data}` before parsing (upstream get_run_policy).
                "/api/v70103/runModePolicy" to policyEnvelope(fixtureText("run_mode_policy.json")),
                "/api/v1/getGeoFenceForRun" to fixtureText("geo_fence.json"),
                // minDistance = 2000 -> fetch issues the five-point query (Task 9 ruling:
                // the production school has no fence passPoints, so checkpoints can only
                // come from /api/v560/get/1/distance/{d}).
                "/api/v560/get/1/distance/2000" to fivePointResponse(
                    """[{"lat":30.0010,"lon":120.0010,"isFixed":1,"pointName":"A"},""" +
                        """{"lat":30.0020,"lon":120.0020,"isFixed":0,"pointName":"B"}]""",
                ),
            ),
            paths,
            requestBodies,
        )

        val policy = PolicyClient(api).fetch(Token("session-value", "uid-42", "90001", null), mode = 1)

        assertEquals(
            listOf("/api/v70103/runModePolicy", "/api/v1/getGeoFenceForRun", "/api/v560/get/1/distance/2000"),
            paths,
        )
        assertEquals("uid-42", api.uid)
        assertEquals("session-value", api.token)
        assertEquals(180, policy.paceMinSecPerKm)
        assertEquals(600, policy.paceMaxSecPerKm)
        assertEquals(10, policy.validFromHour)
        assertEquals(22, policy.validToHour)
        assertEquals(2000, policy.maxDistancePerRunM)
        assertEquals(2000, policy.minDistancePerRunM)
        assertNull(policy.faceWindow)
        // Five-point merge: isFixed=1 -> required, isFixed=0 -> optional (upstream builder).
        assertEquals(listOf(LatLng(30.0010, 120.0010)), policy.requiredPoints)
        assertEquals(listOf(LatLng(30.0020, 120.0020)), policy.optionalPoints)

        // The request itself, decrypted through the Task 3 envelope: no fence -> null-island
        // anchor (documented no-fence path), upstream body fields, and the sign pinned to
        // md5("http://run.gxapp.iydsj.com/api/v560/get/1/distance/2000" + salt) — the same
        // literal upstream's test_checkpoints.py asserts.
        val checkpoint = decryptPostBody(
            requestBodies.getValue("/api/v560/get/1/distance/2000"),
            protocolParams(),
        )
        assertEquals(0.0, checkpoint.getValue("latitude").jsonPrimitive.content.toDouble())
        assertEquals(0.0, checkpoint.getValue("longitude").jsonPrimitive.content.toDouble())
        assertEquals(90001, checkpoint.getValue("selectedUnid").jsonPrimitive.content.toInt())
        assertEquals(-1, checkpoint.getValue("runAreaId").jsonPrimitive.content.toInt())
        assertEquals("", checkpoint.getValue("runec").jsonPrimitive.content)
        assertEquals(36, checkpoint.getValue("uuid").jsonPrimitive.content.length)
        assertEquals(
            "45732c140c185c94e096316a297a6065",
            checkpoint.getValue("sign").jsonPrimitive.content,
        )
    }

    @Test
    fun `fetch threads the campus selectUnid into the policy and the five-point bodies`() {
        // The campus switch reaches BOTH rule reads through this one argument, so the
        // run executes against the campus Home displays (display/execution agreement).
        val paths = mutableListOf<String>()
        val requestBodies = mutableMapOf<String, String>()
        val api = apiServing(
            mapOf(
                "/api/v70103/runModePolicy" to policyEnvelope(fixtureText("run_mode_policy.json")),
                "/api/v1/getGeoFenceForRun" to fixtureText("geo_fence.json"),
                "/api/v560/get/1/distance/2000" to fivePointResponse(
                    """[{"lat":30.0010,"lon":120.0010,"isFixed":1,"pointName":"A"}]""",
                ),
            ),
            paths,
            requestBodies,
        )

        PolicyClient(api).fetch(
            Token("session-value", "uid-42", "90001", null),
            mode = 1,
            selectUnid = "35103",
        )

        val policy = decryptPostBody(requestBodies.getValue("/api/v70103/runModePolicy"), protocolParams())
        assertEquals(35103, policy.getValue("selectUnid").jsonPrimitive.content.toInt(), "policy selectUnid")
        val checkpoint = decryptPostBody(requestBodies.getValue("/api/v560/get/1/distance/2000"), protocolParams())
        assertEquals(35103, checkpoint.getValue("selectedUnid").jsonPrimitive.content.toInt(), "five-point unid")
    }

    @Test
    fun `fetch five-point business error leaves checkpoints empty but still called`() {
        val paths = mutableListOf<String>()
        val api = apiServing(
            mapOf(
                "/api/v70103/runModePolicy" to policyEnvelope(fixtureText("run_mode_policy.json")),
                "/api/v1/getGeoFenceForRun" to fixtureText("geo_fence.json"),
                "/api/v560/get/1/distance/2000" to """{"error":-10000,"message":"ERROR","data":null}""",
            ),
            paths,
        )

        val policy = PolicyClient(api).fetch(Token("session-value", "uid-42", "90001", null), mode = 1)

        assertTrue(paths.contains("/api/v560/get/1/distance/2000"), "five-point must be attempted: $paths")
        assertTrue(policy.requiredPoints.isEmpty())
        assertTrue(policy.optionalPoints.isEmpty())
    }

    @Test
    fun `fetch skips five-point when the fence already carries passPoints`() {
        val paths = mutableListOf<String>()
        val fenceWithPassPoints = Json.parseToJsonElement(
            """{"error":10000,"message":"成功","data":{"geoFences":[{
              "id":1,"name":"s",
              "points":[{"lat":30.0,"lng":120.0},{"lat":30.0,"lng":120.01},{"lat":30.01,"lng":120.01}],
              "passPoints":[{"lat":30.005,"lon":120.005,"isFixed":1}]
            }]}}""",
        )
        val api = apiServing(
            mapOf(
                "/api/v70103/runModePolicy" to policyEnvelope(fixtureText("run_mode_policy.json")),
                "/api/v1/getGeoFenceForRun" to fenceWithPassPoints.toString(),
                "/api/v560/get/1/distance/2000" to fivePointResponse("""[{"lat":0.0,"lon":0.0}]"""),
            ),
            paths,
        )

        val policy = PolicyClient(api).fetch(Token("session-value", "uid-42", "90001", null), mode = 1)

        // Upstream execute_full_run only queries checkpoints when the fence has none.
        assertEquals(listOf("/api/v70103/runModePolicy", "/api/v1/getGeoFenceForRun"), paths)
        assertEquals(listOf(LatLng(30.005, 120.005)), policy.requiredPoints)
    }

    @Test
    fun `fetch maps a business error policy envelope to an empty rule but keeps the fence`() {
        val paths = mutableListOf<String>()
        val api = apiServing(
            mapOf(
                "/api/v70103/runModePolicy" to """{"error":-10000,"message":"ERROR","data":null}""",
                "/api/v1/getGeoFenceForRun" to fixtureText("geo_fence.json"),
            ),
            paths,
        )

        val policy = PolicyClient(api).fetch(Token("session-value", "uid-42", "90001", null), mode = 1)

        // Upstream get_run_policy returns None for a business error -> valid empty policy,
        // while the fence call still runs and its (empty) geometry is preserved.
        assertEquals(0, policy.paceMinSecPerKm)
        assertEquals(0, policy.paceMaxSecPerKm)
        assertEquals(0, policy.validFromHour)
        assertEquals(0, policy.validToHour)
        assertEquals(0, policy.maxDistancePerRunM)
        assertNull(policy.faceWindow)
        assertTrue(policy.fence.isEmpty())
        assertTrue(policy.requiredPoints.isEmpty())
        assertTrue(policy.optionalPoints.isEmpty())
    }

    @Test
    fun `fetch accepts the out of window envelope and indoor mode uses its own path`() {
        val paths = mutableListOf<String>()
        val api = apiServing(
            mapOf(
                "/api/v59/indoorRunModePolicy" to policyEnvelope(
                    """{"policy":1,"timestamp":1794816298979,"message":null,"runRuleModel":null}""",
                ),
                "/api/v1/getGeoFenceForRun" to """{"error":-10000,"message":"ERROR","data":null}""",
            ),
            paths,
        )

        val policy = PolicyClient(api).fetch(Token("session-value", "uid-42", "90001", null), mode = 5)

        assertEquals(listOf("/api/v59/indoorRunModePolicy", "/api/v1/getGeoFenceForRun"), paths)
        assertEquals(0, policy.paceMinSecPerKm)
        assertEquals(0, policy.validFromHour)
        assertTrue(policy.fence.isEmpty())
    }

    @Test
    fun `non 10000 policy document is an empty rule not malformed`() {
        // Also covers the parser-level guard: a document that still carries its own
        // non-10000 error must behave like the fence envelope, not throw.
        val envelope = Json.parseToJsonElement(
            """{"error":-10000,"message":"ERROR","data":{"runRuleModel":{"speedTop":3.0}}}""",
        )

        val policy = parseRunPolicy(envelope, fixture("geo_fence.json"))

        assertEquals(0, policy.paceMinSecPerKm)
        assertEquals(0, policy.validFromHour)
        assertTrue(policy.fence.isEmpty())
    }

    private fun emptyFence(): JsonElement = Json.parseToJsonElement(
        """{"error":10000,"message":"成功","data":{"updateTime":1561910400000,"geoFences":null}}""",
    )

    /** Production runModePolicy depth: `{error, message, data}` with the rule body in `data`. */
    private fun policyEnvelope(ruleBody: String): String =
        """{"error":10000,"message":"成功","data":$ruleBody}"""

    /** Production five-point depth: `{error, message, data:{pointsResModels:[...]}}`. */
    private fun fivePointResponse(pointsJson: String): String =
        """{"error":10000,"message":"成功","data":{"pointsResModels":$pointsJson,"sportStartTime":1794816000000}}"""

    private fun fenceWithPoints(pointsJson: String): JsonElement = Json.parseToJsonElement(
        """{"error":10000,"message":"成功","data":{"geoFences":[{"id":1,"name":"s","points":$pointsJson}]}}""",
    )

    private fun fixture(name: String): JsonElement = Json.parseToJsonElement(fixtureText(name))

    private fun fixtureText(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream("policy/$name")) {
            "missing policy/$name fixture"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun apiServing(
        bodies: Map<String, String>,
        paths: MutableList<String>,
        requestBodies: MutableMap<String, String>? = null,
    ): ApiClient {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val path = request.url.encodedPath
                paths += path
                if (requestBodies != null && request.body != null) {
                    val buffer = Buffer()
                    request.body!!.writeTo(buffer)
                    requestBodies[path] = buffer.readUtf8()
                }
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

    /** Reassemble `k || d` (Task 3 envelope) and return the decrypted POST body object. */
    private fun decryptPostBody(wireText: String, params: ProtocolParams): JsonObject {
        val wire = Json.parseToJsonElement(wireText).jsonObject
        val envelope = Base64.getDecoder().decode(wire.getValue("k").jsonPrimitive.content) +
            Base64.getDecoder().decode(wire.getValue("d").jsonPrimitive.content)
        val inner = Json.parseToJsonElement(NetSecKit.decrypt(envelope, params).decodeToString()).jsonObject
        return Json.parseToJsonElement(
            String(Base64.getDecoder().decode(inner.getValue("data").jsonPrimitive.content), Charsets.UTF_8),
        ).jsonObject
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
