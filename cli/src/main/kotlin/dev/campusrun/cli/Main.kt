package dev.campusrun.cli

import dev.campusrun.core.api.ApiClient
import dev.campusrun.core.config.FileParamsKeyStore
import dev.campusrun.core.config.ParamsStore
import dev.campusrun.core.config.ProtocolParams
import dev.campusrun.core.crypto.NetSecKit
import dev.campusrun.core.crypto.Signer
import dev.campusrun.core.identity.DeviceIdentity
import dev.campusrun.core.policy.LatLng
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
import dev.campusrun.core.upload.dailySelfLimitRefusal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.security.SecureRandom
import java.time.Instant
import java.time.format.DateTimeFormatter
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
 * verify  read-only: latest history record, exit 0 iff a record was read back
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
          verify                               回查最新记录，有记录退出码 0
        会话经环境变量传入：CAMPUS_TOKEN / CAMPUS_UID / CAMPUS_UNID
        本机身份（线钥 + 设备盐）首次运行生成并持久化于 ~/.campusrun/，每台机器独立
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
 * `HOST_RUN`, the request RSA public key and the protocol sign salt are protocol
 * constants. `aesKey`/`kdfSalt` are **per-install random** values — generated on the
 * first run, persisted under `~/.campusrun/` through [ParamsStore] over a file-backed
 * [FileParamsKeyStore] (the same ASCII-hex T12 ruling the App's ParamsStore applies),
 * reloaded on every later run. The literals below are only the *bundled fallback* used
 * when persistence is unavailable (loudly logged by ParamsStore), never the steady state:
 * a shared key set would make every clone of this binary present as one install.
 */
internal fun productionParams(): ProtocolParams = installParams.current()

/**
 * The per-install params store, memoised so one process generates at most once (a
 * mid-run key switch would churn keys within one run).
 */
private val installParams: ParamsStore by lazy { paramsStoreFor(identityDir()) }

/** The store over [dir] — seam for tests, which must not write to the real home dir. */
internal fun paramsStoreFor(dir: File): ParamsStore = ParamsStore(
    bundled = bundledParams(),
    keyStore = FileParamsKeyStore(File(dir, PARAMS_FILE)),
    log = { message -> System.err.println(message) },
)

/** Bundled protocol constants + the shared-key fallback (see [productionParams]). */
private fun bundledParams(): ProtocolParams = ProtocolParams(
    aesKey = "0123456789abcdef".toByteArray(Charsets.US_ASCII),
    // Exactly four 10-byte keyData segments (Signer/NetSecKit require 40 bytes; a live
    // probe caught a 44-byte literal here before any request left the device).
    kdfSalt = "abcdefghijklmnopqrstuvwxyz0123456789ABCD".toByteArray(Charsets.US_ASCII),
    rsaPublicKey = RSA_PUBLIC_KEY_B64,
    endpointBase = "https://run.gxapp.iydsj.com",
    signSalt = "2slhe02lsfiwowlcixisla_sls-_slaor",
    versionTag = 1,
)

/** Per-install identity root: `~/.campusrun/` (wire keys + device salt), created on demand. */
internal fun identityDir(): File = File(System.getProperty("user.home"), ".campusrun")

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
    "DeviceId" to DeviceIdentity(deviceSalt, emptyMap()).stableId(),
    "osVersion" to "13",
    "User-Agent" to "okhttp/4.12.0",
)

/** The per-install device salt, memoised so one process never re-derives the DeviceId. */
private val deviceSalt: ByteArray by lazy { loadDeviceSalt(identityDir()) }

/**
 * Spec §5.5-3 device identity: a random salt generated on the first run and persisted
 * under [dir] as `device-salt.bin`, reloaded on every later run — so the CLI's `DeviceId`
 * is stable per install but distinct from every other install (the review defect where a
 * source constant made all clones present as ONE device). When the file cannot be read or
 * written, this falls back to [FALLBACK_DEVICE_ID_SALT]: stable (never churned — churn
 * would look like a new device to the server) but SHARED, and logged loudly, exactly like
 * [ParamsStore]'s bundled-key fallback.
 */
internal fun loadDeviceSalt(dir: File): ByteArray {
    val file = File(dir, SALT_FILE)
    val existing = runCatching { if (file.isFile) file.readBytes() else null }
        .getOrElse { e ->
            System.err.println("identity: device salt read failed, regenerating: $e")
            null
        }
    if (existing != null && existing.isNotEmpty()) return existing
    val fresh = ByteArray(SALT_BYTES).also { SECURE_RANDOM.nextBytes(it) }
    val persisted = runCatching {
        file.parentFile?.mkdirs()
        file.writeBytes(fresh)
        true
    }.getOrElse { e ->
        System.err.println(
            "identity: device salt persist failed, staying on the SHARED constant salt until " +
                "persistence works: $e",
        )
        false
    }
    return if (persisted) fresh else FALLBACK_DEVICE_ID_SALT
}

/**
 * Last-resort salt when `~/.campusrun/device-salt.bin` is unwritable — the pre-review
 * constant, kept only so the DeviceId never churns between runs of one machine. It is
 * shared by every install that hits this path (the defect being fixed), hence the loud
 * log in [loadDeviceSalt]; a writable home dir always wins.
 */
private val FALLBACK_DEVICE_ID_SALT = "campusrun/cli/device-identity/v1".toByteArray(Charsets.UTF_8)

/** Upstream `crypto.py REQ_RSA_PKCS1_B64` (public by construction). */
private val RSA_PUBLIC_KEY_B64 = (
    "MIGJAoGBALmmqVPOwY1mTVHGEfg7jHck1MNXsVtUfrqY99bm/W2cjLi3LvG/wMYw" +
        "bhmf9O+y3CUlZ5g4AMd2Ly4XbWUloG/O8+1USGJ8ddmrzbl8j2EZi0OhpVCYI381" +
        "+zZ0qa3z1cOuG2XdJnE7pO6Y4JNC37miqMXYmjejVi2QDIbONomfAgMBAAE="
    )

/** File names under [identityDir]. */
private const val PARAMS_FILE = "params.bin"
private const val SALT_FILE = "device-salt.bin"

/** Device salt length: 32 bytes of entropy, hashed by [DeviceIdentity] into a 32-hex DeviceId. */
private const val SALT_BYTES = 32

private val SECURE_RANDOM = SecureRandom()

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
    runsToday: Int?,
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

    // Fail-closed admission for the 每日次数上限 gate: an advertised daily limit without
    // an explicit --runs-today must refuse here, before synthesis or any submission.
    dailyLimitAdmission(runsToday, policy)?.let { message ->
        throw StageFailure("gate", message)
    }

    // Five-point answers must look like a campus: guards the no-fence anchor path
    // against anchor-relative issuance artefacts before anything is synthesized.
    val stray = (policy.requiredPoints + policy.optionalPoints)
        .firstOrNull { it.lat !in CHINA_MIN_LAT..CHINA_MAX_LAT || it.lng !in CHINA_MIN_LNG..CHINA_MAX_LNG }
    if (stray != null) {
        throw StageFailure("policy", "checkpoint $stray lies outside the China bounding box — refusing to synthesize")
    }

    // No server-side polygon (geoFences = null, the live school): derive synthesis
    // geometry from the issued checkpoints. The REAL policy still drives validation
    // (empty fence -> no 出围栏 rule) and settlement — nothing fence-derived is submitted.
    val checkpoints = policy.requiredPoints + policy.optionalPoints

    // Friendly precondition gate (parity with the App's RunViewModel; 11-25 incident): a
    // five-point answer that came back EMPTY on a no-fence school leaves no geometry to
    // synthesize, so refuse HERE — Chinese, actionable, counts logged — instead of the
    // synthesizer's English `require`, and before any upload/settle leg.
    synthGeometryRefusal(policy)?.let { message ->
        log(noGeometryDiagnosis(policy, checkpoints))
        throw StageFailure("synth", message)
    }

    val geometryFence = when {
        policy.fence.size >= 3 -> policy.fence
        checkpoints.isNotEmpty() -> deriveFence(checkpoints)
        else -> policy.fence
    }
    if (geometryFence !== policy.fence) {
        log(
            "synth: derived ${geometryFence.size}-point fence around ${checkpoints.size} checkpoints " +
                "(school advertises no geoFences)",
        )
    }
    if (checkpoints.isNotEmpty() && checkpoints.size <= 8) {
        log("checkpoints: " + checkpoints.joinToString { "${it.lat},${it.lng}" })
    }

    // dayGoal is a *daily* total (upstream's own default run is 2100 m against a
    // dayGoal-2000 school, and its builder never enforces it per run), so a per-run
    // target above it lifts the synthesis cap; the validator's per-run floor still
    // gates the submission below.
    val synthBase = if (policy.maxDistancePerRunM in 1 until distanceM) {
        policy.copy(fence = geometryFence, maxDistancePerRunM = 0)
    } else {
        policy.copy(fence = geometryFence)
    }

    // Route through ALL issued checkpoints when the geometry can host it — upstream's
    // builder uses every checkpoint as a waypoint and its consistency check demands the
    // track touch each one — falling back to required-only when the pinned tour alone
    // would overrun the target (passing the isFixed=1 points is the hard requirement).
    val track = try {
        TrackSynthesizer.synthesize(synthBase.copy(requiredPoints = checkpoints), distanceM, durationSec, seed)
    } catch (allPoints: IllegalArgumentException) {
        log("synth: all-checkpoint routing unavailable (${allPoints.message}); falling back to required-only")
        try {
            TrackSynthesizer.synthesize(synthBase, distanceM, durationSec, seed)
        } catch (requiredOnly: IllegalArgumentException) {
            // Belt and suspenders: the gate above makes the empty-geometry case
            // unreachable here — detect it STRUCTURALLY (the fence handed to synthesis
            // has < 3 points) rather than pattern-matching the synthesizer's English
            // require text, so its raw wording can never reach the operator's output.
            if (geometryFence.size < 3) {
                log(noGeometryDiagnosis(policy, checkpoints))
                throw StageFailure("synth", NO_GEOMETRY_MESSAGE)
            }
            throw StageFailure("synth", requiredOnly.message ?: "fence/checkpoint geometry cannot host the run")
        }
    }
    log("synth: seed $seed, ${track.size} samples at 1 Hz")

    val nowMs = System.currentTimeMillis() + skewMs
    val violations = RunPolicyValidator.validate(track, policy, nowMs) +
        RunPolicyValidator.validateDailyLimit(runsToday ?: 0, policy)
    if (violations.isNotEmpty()) {
        log("gate: REFUSED — ${violations.joinToString("；")}")
        return RunOutcome(skewMs, policy, track.size, violations, uploaded = false, rrid = null, record = null)
    }
    log("gate: passed (fence/checkpoints/pace/distance/window/face/daily)")

    // Cross-session 每日 ≤1 (spec §5.5-4 #1): the newest history record must not already
    // be today's. Read-only, immediately before the upload — the operator's --runs-today
    // assertion no longer carries this rule on its own.
    dailySelfLimitRefusal(HistoryClient(api).latest(token), nowMs)?.let { message ->
        log("gate: REFUSED — $message")
        return RunOutcome(
            skewMs,
            policy,
            track.size,
            listOf(message),
            uploaded = false,
            rrid = null,
            record = null,
        )
    }

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

/**
 * Friendly pre-synthesis refusal for the 11-25 empty-geometry incident: a school with NO
 * server polygon (geoFences absent) derives synthesis geometry from the issued
 * checkpoints, so `fence < 3 points AND no checkpoints` means there is nothing to
 * inscribe a loop in. Returns the actionable Chinese message, or `null` when synthesis
 * has geometry to work with (a real polygon, or checkpoints to derive one from) — the
 * same structural condition as the App's `RunViewModel` gate. Pure so it is testable
 * offline (`executeRun` itself needs the live protocol).
 */
internal fun synthGeometryRefusal(policy: RunPolicy): String? =
    if (policy.fence.size < 3 && (policy.requiredPoints + policy.optionalPoints).isEmpty()) {
        NO_GEOMETRY_MESSAGE
    } else {
        null
    }

/** Diagnosis line for the refusal: what the pipeline saw (counts + advertised window). */
private fun noGeometryDiagnosis(policy: RunPolicy, checkpoints: List<LatLng>): String =
    "synth: REFUSED — fence ${policy.fence.size} points, checkpoints ${checkpoints.size}, " +
        "window ${policy.validFromHour}-${policy.validToHour} h (no geometry to synthesize)"

/**
 * The refusal sentence itself — names the cause (no polygon → checkpoints required), the
 * likely reasons (transient five-point failure, or outside the issue window) and the
 * retry action. The App's `RunViewModel.NO_GEOMETRY_MESSAGE` carries the same sentence
 * (its tail points at the Home refresh button, which the CLI has no equivalent of).
 */
private const val NO_GEOMETRY_MESSAGE =
    "点位数据未获取到（学校无围栏，合成需要至少 3 个点位）——" +
        "可能是网络瞬时失败或不在下发时段，请稍后重试"

/**
 * Bounding-box polygon (+padding) around the issued checkpoints, for schools that
 * advertise no `geoFences` polygon but do issue checkpoints (the live school):
 * [TrackSynthesizer] needs a polygon to inscribe the loop in. Synthesis geometry only —
 * validation runs against the real policy and settlement never sees this fence.
 */
private fun deriveFence(checkpoints: List<LatLng>): List<LatLng> {
    val latMin = checkpoints.minOf { it.lat }
    val latMax = checkpoints.maxOf { it.lat }
    val lngMin = checkpoints.minOf { it.lng }
    val lngMax = checkpoints.maxOf { it.lng }
    val latHalf = maxOf((latMax - latMin) / 2 + FENCE_PAD_DEG, FENCE_MIN_HALF_DEG)
    val lngHalf = maxOf((lngMax - lngMin) / 2 + FENCE_PAD_DEG, FENCE_MIN_HALF_DEG)
    val latCentre = (latMin + latMax) / 2
    val lngCentre = (lngMin + lngMax) / 2
    return listOf(
        LatLng(latCentre - latHalf, lngCentre - lngHalf),
        LatLng(latCentre + latHalf, lngCentre - lngHalf),
        LatLng(latCentre + latHalf, lngCentre + lngHalf),
        LatLng(latCentre - latHalf, lngCentre + lngHalf),
    )
}

/** ~110 m of padding in degrees; the floor keeps a degenerate (single-point) box usable. */
private const val FENCE_PAD_DEG = 0.001
private const val FENCE_MIN_HALF_DEG = 0.0015

/**
 * Fail-closed admission for the brief's 每日次数上限 gate (review fix): when the school
 * advertises a daily limit, the operator must state today's count explicitly — an absent
 * `--runs-today` must not read as "0 runs today". Returns the refusal message, or null to
 * proceed (the count then flows into [RunPolicyValidator.validateDailyLimit]).
 */
internal fun dailyLimitAdmission(runsToday: Int?, policy: RunPolicy): String? =
    if (policy.dailyRunLimit > 0 && runsToday == null) {
        "dailyRunLimit=${policy.dailyRunLimit} but --runs-today not supplied — pass --runs-today <k> (0 if none yet)"
    } else {
        null
    }

// ---------------------------------------------------------------- subcommands

/**
 * One **objective** read-back row: `id · distance · date` (the fixed [RunPolicyValidator.RULE_ZONE]
 * date, or an explicit placeholder when the row carries no usable `startTime`).
 *
 * Deliberately carries **no verdict**: our [RunRecord.valid] diverges from the official
 * 达标 scoring in both directions (11-17 ours `true`/官方 不达标; 11-21 & 11-23 ours
 * `false`/官方 达标), so `verify`/`run` may only state facts — official scoring stays
 * external (grayscale daily official-app check).
 */
internal fun recordFactLine(record: RunRecord): String {
    val date = record.startTimeMs?.let {
        RECORD_DATE_FORMAT.format(Instant.ofEpochMilli(it).atZone(RunPolicyValidator.RULE_ZONE))
    } ?: "日期未知"
    return "id=${record.id} · ${record.distanceM} m · $date"
}

private val RECORD_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

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
        // Exit code keys on the OBJECTIVE fact (a record was read back), never on the
        // divergent `valid` flag — see recordFactLine.
        when {
            record == null -> {
                println("verify: no records")
                1
            }
            else -> {
                println("verify: ${recordFactLine(record)}")
                0
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
                System.err.println("提交已接受 (rrid=${outcome.rrid}) 但回查无记录")
                1
            }
            else -> {
                // Settlement already succeeded (rrid above); read-back reports objective
                // facts only — never a verdict from the divergent `valid` flag.
                println("回查记录已写入：${recordFactLine(outcome.record)}")
                0
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

internal data class RunOptions(val distance: Int, val time: Int, val seed: Long, val runsToday: Int?)

internal fun parseRunOptions(args: List<String>): RunOptions? {
    var distance: Int? = null
    var time: Int? = null
    var seed = Random.nextLong()
    var runsToday: Int? = null
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
    if (runsToday != null && runsToday < 0) return runUsage("--runs-today 必须非负")
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
