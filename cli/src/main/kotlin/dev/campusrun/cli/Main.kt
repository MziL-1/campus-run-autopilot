package dev.campusrun.cli

import dev.campusrun.core.api.ApiClient
import dev.campusrun.core.config.ProtocolParams
import dev.campusrun.core.crypto.NetSecKit
import dev.campusrun.core.crypto.Signer
import dev.campusrun.core.identity.DeviceIdentity
import dev.campusrun.core.policy.PolicyClient
import dev.campusrun.core.policy.RunPolicy
import dev.campusrun.core.policy.RunPolicyValidator
import dev.campusrun.core.session.TimeSync
import dev.campusrun.core.session.Token
import dev.campusrun.core.track.TrackSynthesizer
import dev.campusrun.core.upload.HistoryClient
import dev.campusrun.core.upload.RunRecord
import dev.campusrun.core.upload.RunUploader
import dev.campusrun.core.upload.Settlement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.random.Random
import kotlin.system.exitProcess

/**
 * One-shot CLI over the protocol core (plan Task 9). Three subcommands, all thin
 * orchestration — every protocol decision lives in `core`:
 *
 * ```text
 * probe   read-only: clock skew, run rule, fence/checkpoint geometry, latest record
 * run     sync -> policy -> synth -> validate (any violation refuses submission)
 *         -> OBS upload -> save/record settle -> history verify
 * verify  read-only: latest history record, exit 0 iff valid == true
 * ```
 *
 * The session comes from `CAMPUS_TOKEN` / `CAMPUS_UID` / `CAMPUS_UNID` (env only —
 * Kotlin gets credential login in Task 12, so a token minted by the upstream login
 * path is the only accepted input; nothing here reads or writes credentials).
 */
fun main(args: Array<String>) {
    val exitCode = when (args.firstOrNull()) {
        "probe" -> probeCommand()
        "run" -> runCommand(args.drop(1))
        "verify" -> verifyCommand()
        else -> {
            printUsage()
            2
        }
    }
    exitProcess(exitCode)
}

private fun printUsage() {
    println(
        """
        campusrun-autopilot CLI
          probe                                读取时钟偏斜/跑步规则/围栏点位/最新记录（不提交）
          run --distance <m> --time <s> [--seed <n>] [--runs-today <n>]
                                               一键跑：校验通过才提交，否则拒绝
          verify                               回查最新记录，达标退出码 0
        会话经环境变量传入：CAMPUS_TOKEN / CAMPUS_UID / CAMPUS_UNID
        """.trimIndent(),
    )
}

// ---------------------------------------------------------------- session & wiring

/** The env-only session (ruling: no credential login in Kotlin until Task 12). */
internal fun sessionFromEnv(): Token? {
    val value = System.getenv("CAMPUS_TOKEN")?.takeIf { it.isNotBlank() } ?: return null
    val uid = System.getenv("CAMPUS_UID")?.takeIf { it.isNotBlank() } ?: return null
    val unid = System.getenv("CAMPUS_UNID").orEmpty()
    return Token(value = value, uid = uid, unid = unid, expiresAtMs = null)
}

/**
 * Production wire parameters (upstream `core/config.py` + `core/crypto.py`): endpoint
 * `HOST_RUN`, the request RSA public key, and the protocol sign salt. `aesKey`/`kdfSalt`
 * are upstream per-request randomness pinned here to stable values — Task 3's documented
 * forced-determinism deviation: the server unwraps the key from each request and derives
 * the response key from the keyData segments the request itself carries, so any stable
 * 16/40-byte value works (these are protocol constants, not credentials).
 */
internal fun productionParams(): ProtocolParams = ProtocolParams(
    aesKey = "0123456789abcdef".toByteArray(Charsets.US_ASCII),
    kdfSalt = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGH".toByteArray(Charsets.US_ASCII),
    rsaPublicKey = RSA_PUBLIC_KEY_B64,
    endpointBase = "https://run.gxapp.iydsj.com",
    signSalt = "2slhe02lsfiwowlcixisla_sls-_slaor",
    versionTag = 1,
)

/** Upstream `DEFAULT_DEVICE_CONFIG` header set with a stable tool-side DeviceId. */
internal fun deviceHeaders(): Map<String, String> = mapOf(
    "Accept" to "application/json",
    "Content-Type" to "application/json",
    "appVersion" to "7.3.92",
    "physicPixel" to "1080x2400",
    "logicPixel" to "393x873",
    "isRoot" to "0",
    "osType" to "0",
    "cpuModel" to "arm64-v8a",
    "deviceName" to "Android Device",
    "DeviceId" to DeviceIdentity(DEVICE_ID_SALT, emptyMap()).stableId(),
    "osVersion" to "13",
    "User-Agent" to "okhttp/4.12.0",
)

/**
 * Stable tool-side device identity (spec §5.5-3: persisted, never churned). A source
 * constant is the CLI's persistence — the id is identical on every run of this tool
 * and distinct from any other install's salt.
 */
private val DEVICE_ID_SALT = "campusrun/cli/device-identity/v1".toByteArray(Charsets.UTF_8)

/** Upstream `crypto.py REQ_RSA_PKCS1_B64` (public by construction). */
private val RSA_PUBLIC_KEY_B64 = (
    "MIGJAoGBALmmqVPOwY1mTVHGEfg7jHck1MNXsVtUfrqY99bm/W2cjLi3LvG/wMYw" +
        "bhmf9O+y3CUlZ5g4AMd2Ly4XbWUloG/O8+1USGJ8ddmrzbl8j2EZi0OhpVCYI381" +
        "+zZ0qa3z1cOuG2XdJnE7pO6Y4JNC37miqMXYmjejVi2QDIbONomfAgMBAAE="
    )

// ---------------------------------------------------------------- run orchestration

/** Every stage a live run passes; `violations` non-empty means submission was refused. */
internal data class RunOutcome(
    val skewMs: Long,
    val policy: RunPolicy,
    val sampleCount: Int,
    val violations: List<String>,
    val uploaded: Boolean,
    val rrid: String?,
    val record: RunRecord?,
)

/** Carries the failing stage to the CLI exit path and the live test's assertion text. */
internal class StageFailure(val stage: String, message: String) : RuntimeException("[$stage] $message")

private const val SAVE_RECORD_PATH = "/api/v70260/runnings/save/record"

/** Sanity fence for issued checkpoints: mainland-China bounding box (risk control §5.5). */
private const val CHINA_MIN_LAT = 5.0
private const val CHINA_MAX_LAT = 55.0
private const val CHINA_MIN_LNG = 70.0
private const val CHINA_MAX_LNG = 140.0

/**
 * The brief's `run` pipeline: sync -> policy -> synth -> **validate (refuse on any
 * violation)** -> upload -> settle -> verify. Failures before the gate throw
 * [StageFailure] with the stage name; a gate refusal returns without touching the
 * network beyond the policy fetch. `nowEpochMs` includes the measured server skew.
 */
internal fun executeRun(
    token: Token,
    distanceM: Int,
    durationSec: Int,
    seed: Long,
    runsToday: Int,
    log: (String) -> Unit = {},
): RunOutcome {
    val params = productionParams()
    val api = ApiClient(params = params, deviceHeaders = deviceHeaders())

    val timeSync = TimeSync(api)
    val skewMs = timeSync.serverOffsetMs() // stage: sync
    log("sync: server clock skew ${skewMs} ms")

    val policy = PolicyClient(api).fetch(token, mode = 1) // stage: policy
    log(
        "rule: window ${policy.validFromHour}-${policy.validToHour} h, " +
            "pace ${policy.paceMinSecPerKm}-${policy.paceMaxSecPerKm} s/km, " +
            "minDistance ${policy.minDistancePerRunM} m, dayGoal ${policy.maxDistancePerRunM} m, " +
            "dailyLimit ${policy.dailyRunLimit}",
    )
    log(
        "geometry: fence ${policy.fence.size}, required ${policy.requiredPoints.size}, " +
            "optional ${policy.optionalPoints.size}",
    )

    // Upstream stops when `get_run_policy` returns no rule ("未取得学校规则，停止提交"):
    // no advertised pace window AND no advertised time window means the rule body was
    // absent (run window passed, or the session was rejected) — same refusal, clearer stage.
    if (policy.paceMinSecPerKm == 0 && policy.paceMaxSecPerKm == 0 &&
        policy.validFromHour == 0 && policy.validToHour == 0
    ) {
        throw StageFailure(
            "policy",
            "no rule body advertised (outside the school run window, or session rejected) — not submitting",
        )
    }

    // Five-point answers must look like a campus: guards the no-fence anchor path
    // against anchor-relative issuance artefacts before anything is synthesized.
    val stray = (policy.requiredPoints + policy.optionalPoints)
        .firstOrNull { it.lat !in CHINA_MIN_LAT..CHINA_MAX_LAT || it.lng !in CHINA_MIN_LNG..CHINA_MAX_LNG }
    if (stray != null) {
        throw StageFailure("policy", "checkpoint $stray lies outside the China bounding box — refusing to synthesize")
    }

    // dayGoal is a *daily* total (upstream's own default run is 2100 m against a
    // dayGoal-2000 school, and its builder never enforces it per run), so a per-run
    // target above it lifts the synthesis cap; the validator's per-run floor still
    // gates the submission below.
    val synthPolicy = if (policy.maxDistancePerRunM in 1 until distanceM) {
        policy.copy(maxDistancePerRunM = 0)
    } else {
        policy
    }

    val track = try {
        TrackSynthesizer.synthesize(synthPolicy, distanceM, durationSec, seed) // stage: synth
    } catch (e: IllegalArgumentException) {
        throw StageFailure("synth", e.message ?: "fence/checkpoint geometry cannot host the run")
    }
    log("synth: seed $seed, ${track.size} samples at 1 Hz")

    val nowMs = System.currentTimeMillis() + skewMs
    val violations = RunPolicyValidator.validate(track, policy, nowMs) +
        RunPolicyValidator.validateDailyLimit(runsToday, policy)
    if (violations.isNotEmpty()) {
        log("gate: REFUSED — ${violations.joinToString("；")}")
        return RunOutcome(skewMs, policy, track.size, violations, uploaded = false, rrid = null, record = null)
    }
    log("gate: passed (fence/checkpoints/pace/distance/window/face/daily)")

    val handle = RunUploader(api, NetSecKit, Signer, timeSync).uploadTrack(token, track) // stage: upload
    log("upload: OBS push accepted, uuid ${handle.uuid}")

    val body = Json.parseToJsonElement(
        String(Settlement.buildRequest(policy, track, handle, params), Charsets.UTF_8),
    )
    val response = api.post(SAVE_RECORD_PATH, body) // stage: settle
    val rrid = settleRrid(response)
    log("settle: save/record accepted, rrid=$rrid")

    Thread.sleep(1_500) // upstream waits before the read-back (0.5 s); give the store room
    val record = HistoryClient(api).latest(token) // stage: verify
    log(
        "verify: " + (record?.let { "id=${it.id}, ${it.distanceM} m, valid=${it.valid}" } ?: "no record returned"),
    )
    return RunOutcome(skewMs, policy, track.size, emptyList(), uploaded = true, rrid = rrid, record = record)
}

/** Upstream `submit_record` success check: `error == 10000`, `data.rrid | data.id`. */
private fun settleRrid(response: JsonElement): String? {
    val root = response as? JsonObject
        ?: throw StageFailure("settle", "save/record response must be a JSON object")
    val error = (root["error"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()
    if (error != 10_000L) {
        val message = (root["message"] as? JsonPrimitive)?.contentOrNull ?: "no message"
        throw StageFailure("settle", "server rejected the record: error=$error message=$message")
    }
    val data = root["data"] as? JsonObject ?: return null
    val rrid = (data["rrid"] as? JsonPrimitive)?.contentOrNull?.takeIf { it != "null" }
        ?: (data["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it != "null" }
    return rrid
}

// ---------------------------------------------------------------- subcommands

private fun probeCommand(): Int {
    val token = sessionFromEnv() ?: return missingSession()
    return try {
        val api = ApiClient(params = productionParams(), deviceHeaders = deviceHeaders())
        val skewMs = TimeSync(api).serverOffsetMs()
        println("sync: server clock skew $skewMs ms")

        val policy = PolicyClient(api).fetch(token, mode = 1)
        println(
            "rule: window ${policy.validFromHour}-${policy.validToHour} h, " +
                "pace ${policy.paceMinSecPerKm}-${policy.paceMaxSecPerKm} s/km, " +
                "minDistance ${policy.minDistancePerRunM} m, dayGoal ${policy.maxDistancePerRunM} m, " +
                "dailyLimit ${policy.dailyRunLimit}, faceWindow ${policy.faceWindow ?: "none"}",
        )
        println(
            "geometry: fence ${policy.fence.size}, required ${policy.requiredPoints.size}, " +
                "optional ${policy.optionalPoints.size}" +
                policy.requiredPoints.firstOrNull().let { ", first required $it" },
        )

        val record = HistoryClient(api).latest(token)
        println("history: " + (record?.let { "id=${it.id}, ${it.distanceM} m, valid=${it.valid}" } ?: "none"))
        0
    } catch (e: Exception) {
        System.err.println("probe failed: ${e.message}")
        1
    }
}

private fun verifyCommand(): Int {
    val token = sessionFromEnv() ?: return missingSession()
    return try {
        val api = ApiClient(params = productionParams(), deviceHeaders = deviceHeaders())
        val record = HistoryClient(api).latest(token)
        when {
            record == null -> {
                println("verify: no records")
                1
            }
            record.valid -> {
                println("verify: id=${record.id}, ${record.distanceM} m, valid=true")
                0
            }
            else -> {
                println("verify: id=${record.id}, ${record.distanceM} m, valid=false (未达标)")
                1
            }
        }
    } catch (e: Exception) {
        System.err.println("verify failed: ${e.message}")
        1
    }
}

private fun runCommand(args: List<String>): Int {
    val options = parseRunOptions(args) ?: return 2
    val token = sessionFromEnv() ?: return missingSession()
    return try {
        val outcome = executeRun(
            token = token,
            distanceM = options.distance,
            durationSec = options.time,
            seed = options.seed,
            runsToday = options.runsToday,
            log = ::println,
        )
        when {
            outcome.violations.isNotEmpty() -> {
                System.err.println("拒绝提交（闸门违规）：")
                outcome.violations.forEach { System.err.println("  - $it") }
                1
            }
            !outcome.uploaded -> 1
            outcome.record == null -> {
                System.err.println("提交已接受 (rrid=${outcome.rrid}) 但回查无记录，无法判定")
                1
            }
            outcome.record.valid -> {
                println("完成：达标 distance=${outcome.record.distanceM} m id=${outcome.record.id}")
                0
            }
            else -> {
                System.err.println("回查未达标：distance=${outcome.record.distanceM} m id=${outcome.record.id}")
                1
            }
        }
    } catch (e: StageFailure) {
        System.err.println("失败 ${e.message}")
        1
    } catch (e: Exception) {
        System.err.println("失败: ${e.message}")
        1
    }
}

private data class RunOptions(val distance: Int, val time: Int, val seed: Long, val runsToday: Int)

private fun parseRunOptions(args: List<String>): RunOptions? {
    var distance: Int? = null
    var time: Int? = null
    var seed = Random.nextLong()
    var runsToday = 0
    var index = 0
    while (index < args.size) {
        val flag = args[index]
        val value = args.getOrNull(index + 1)
        when (flag) {
            "--distance" -> {
                distance = value?.toIntOrNull()
                if (distance == null) return runUsage("--distance 需要整数米数")
            }
            "--time" -> {
                time = value?.toIntOrNull()
                if (time == null) return runUsage("--time 需要整数秒数")
            }
            "--seed" -> {
                seed = value?.toLongOrNull() ?: return runUsage("--seed 需要整数")
            }
            "--runs-today" -> {
                runsToday = value?.toIntOrNull() ?: return runUsage("--runs-today 需要非负整数")
            }
            else -> return runUsage("未知参数 $flag")
        }
        index += 2
    }
    val finalDistance = distance ?: return runUsage("缺少 --distance <米>")
    val finalTime = time ?: return runUsage("缺少 --time <秒>")
    if (finalDistance <= 0 || finalTime <= 0) return runUsage("--distance/--time 必须为正")
    if (runsToday < 0) return runUsage("--runs-today 必须非负")
    return RunOptions(finalDistance, finalTime, seed, runsToday)
}

private fun runUsage(message: String): Nothing? {
    System.err.println(message)
    System.err.println("用法: run --distance <米> --time <秒> [--seed <n>] [--runs-today <n>]")
    return null
}

private fun missingSession(): Int {
    System.err.println("缺少会话：请设置 CAMPUS_TOKEN / CAMPUS_UID（可选 CAMPUS_UNID）环境变量")
    return 2
}
