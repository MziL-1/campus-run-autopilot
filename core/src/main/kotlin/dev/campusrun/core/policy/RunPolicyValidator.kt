package dev.campusrun.core.policy

import dev.campusrun.core.track.Geo
import dev.campusrun.core.track.TrackPoint
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Pre-submission rule gate (spec §5.5: "只守服务端规则"; plan Task 9): every check that
 * can be answered from the [TrackPoint] track, the server-advertised [RunPolicy] and the
 * submission instant, before anything leaves the device.
 *
 * [validate] returns a **violation list — empty means pass** (brief interface). The gate
 * covers, in order:
 *
 * 1. **出围栏** — every track point must lie inside the fence polygon
 *    ([Geo.insidePolygon], boundary inclusive). Skipped when the school advertises no
 *    fence (`fence` smaller than a polygon: a school without `geoFences` has nothing to
 *    violate).
 * 2. **缺必经点** — every required point must be approached within [PASS_RADIUS_M] of the
 *    track. The synthesizer pins the route exactly through each required point (sampled
 *    at 1 Hz, so sub-speed metres away), which makes 50 m a wide but finite tolerance.
 * 3. **距离不足** — the emitted path length must reach the run's distance floor:
 *    `max(minDistancePerRunM, 2000)`. The 2000 m half is the settlement completeness
 *    floor (`Settlement` sends upstream builder's `selDistance = 2000`; anything shorter
 *    goes out as `complete=false, unCompleteReason=10` and reads back invalid — T8
 *    calibration ruling). A school floor above 2000 (e.g. `minDistance = 3000`) wins.
 * 4. **距离超限** — the emitted path length must stay within the per-run ceiling
 *    `maxDistancePerRunM` (+ [MAX_DISTANCE_SLACK_M] = 30 m of tolerance, the accepted
 *    2030-vs-2000 slack from the 11-17/11-19 live runs, spec C2 单次距离限值).
 *    `maxDistancePerRunM == 0` means "not advertised" → no ceiling. Both shells lift a
 *    sub-target cap to 0 for *synthesis* only, so a too-long request still reaches this
 *    check and is refused here instead of being silently accepted.
 * 5. **配速越界** — mean pace `durationSec * 1000 / distanceM` must sit inside the
 *    advertised `[paceMinSecPerKm, paceMaxSecPerKm]` window (`speedTop`/`speedBottom`,
 *    seconds per km; `speedTop` is the FAST bound, hence the smaller number). Skipped
 *    when the window is not advertised (both 0).
 * 6. **时段外** — the submission instant (epoch ms, caller applies the server clock
 *    skew) must fall inside the school's `validTime` window, read on the rule's own
 *    timezone ([RULE_ZONE], the platform runs Chinese campuses; school hours are wall
 *    clock there). Both bounds 0 = not advertised = no check; `from > to` is treated as
 *    an overnight window. `from` inclusive, `to` exclusive.
 * 7. **人脸窗口内** (spec R6) — when a [RunPolicy.faceWindow] is present, the instant
 *    must be outside it. 7.4.x never advertises a window (ruling: `faceWindow` stays
 *    null), so production skips this; the rule exists for synthetic/future policies.
 *
 * [validateDailyLimit] is the brief's **每日次数上限** leg: it needs today's run count,
 * which no parameter of [validate] carries (the brief fixes that signature), so it is a
 * separate pure function the caller feeds its count from — empty list means pass.
 * `dailyRunLimit == 0` means "not advertised" (7.4.x only exposes `weekCountGoal`),
 * never "zero runs allowed".
 *
 * Deliberately NOT gated here (documented scope): step rate (`TrackPoint.stepRateHz`
 * holds steps-per-MINUTE per Task 7 semantics — spec C2's 70–240 band is enforced by
 * the synthesizer, and no server-side step rule is advertised), the *cumulative* daily
 * total (today's kilometer sum, official-App state — anything the caller cannot know
 * offline; the cross-session 每日 ≤1 rule is gated separately from history in
 * `dailySelfLimitRefusal`, upload package).
 */
object RunPolicyValidator {

    /**
     * Required-point acceptance radius in metres. GPS-realistic (the server compares
     * 1 Hz samples against the issued checkpoint), and ~10x the synthesizer's worst-case
     * sample-to-pinned-vertex gap (half a second at run speed).
     */
    const val PASS_RADIUS_M = 50.0

    /** Upstream builder `selDistance` default — the settlement completeness floor. */
    private const val SETTLEMENT_MIN_DISTANCE_M = 2000

    /**
     * Tolerance on the per-run ceiling, in metres: the 11-17/11-19 live runs submitted
     * 2030 m against a 2000 m `dayGoal` and the server accepted both as valid, so the
     * gate allows exactly that much overshoot (spec C2 单次距离限值; acceptance §2-3).
     */
    const val MAX_DISTANCE_SLACK_M = 30

    /**
     * School `validTime` is wall-clock local time of the Chinese campus platform. Also the
     * day boundary the history-based daily gate (`dailySelfLimitRefusal` in the upload
     * package) reads: the platform counts a run's day in this same zone, so both rules
     * share one clock.
     */
    val RULE_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    /** Rule violations for one prospective submission; empty = pass (brief). */
    fun validate(track: List<TrackPoint>, policy: RunPolicy, nowEpochMs: Long): List<String> {
        val violations = mutableListOf<String>()

        if (policy.fence.size >= 3) {
            val outsideIndex = track.indexOfFirst { !Geo.insidePolygon(LatLng(it.lat, it.lng), policy.fence) }
            if (outsideIndex >= 0) {
                val point = track[outsideIndex]
                violations += "出围栏: 轨迹点 #$outsideIndex (${point.lat},${point.lng}) 不在本校围栏内"
            }
        }

        policy.requiredPoints.forEachIndexed { index, required ->
            val nearestM = track.minOf { Geo.distanceM(LatLng(it.lat, it.lng), required) }
            if (nearestM > PASS_RADIUS_M) {
                violations += "缺必经点: 必经点 #${index + 1} 距轨迹最近 ${nearestM.roundToInt()}m " +
                    "(> ${PASS_RADIUS_M.toInt()}m)"
            }
        }

        if (track.size >= 2) {
            val distanceM = Geo.pathLengthM(track.map { LatLng(it.lat, it.lng) })
            val floorM = maxOf(policy.minDistancePerRunM, SETTLEMENT_MIN_DISTANCE_M)
            if (distanceM < floorM) {
                violations += "距离不足: 轨迹 ${distanceM.roundToInt()}m 低于单次最低 ${floorM}m"
            }
            val ceilingM = policy.maxDistancePerRunM
            if (ceilingM > 0 && distanceM > ceilingM + MAX_DISTANCE_SLACK_M) {
                violations += "距离超限: 轨迹 ${distanceM.roundToInt()}m 超过单次上限 ${ceilingM}m " +
                    "(+${MAX_DISTANCE_SLACK_M}m 容差)"
            }
            if (policy.paceMinSecPerKm > 0 && policy.paceMaxSecPerKm > 0 && distanceM > 0) {
                val durationSec = track.last().elapsedSec - track.first().elapsedSec
                val meanPaceSecPerKm = durationSec * 1000.0 / distanceM
                if (meanPaceSecPerKm < policy.paceMinSecPerKm || meanPaceSecPerKm > policy.paceMaxSecPerKm) {
                    violations += "配速越界: 平均 ${meanPaceSecPerKm.roundToInt()} s/km 不在本校窗口 " +
                        "[${policy.paceMinSecPerKm}, ${policy.paceMaxSecPerKm}] s/km"
                }
            }
        }

        val fromHour = policy.validFromHour
        val toHour = policy.validToHour
        if (!(fromHour == 0 && toHour == 0)) {
            val hour = Instant.ofEpochMilli(nowEpochMs).atZone(RULE_ZONE).hour
            val inside = if (fromHour <= toHour) hour in fromHour until toHour else hour >= fromHour || hour < toHour
            if (!inside) {
                violations += "时段外: 当前 ${hour} 时不在本校有效时段 [$fromHour, $toHour)"
            }
        }

        val faceWindow = policy.faceWindow
        if (faceWindow != null && nowEpochMs in faceWindow) {
            violations += "人脸窗口内: 提交时刻落在人脸抽检窗口 (spec R6)，暂缓提交"
        }

        return violations
    }

    /** 每日次数上限 leg of the gate; `runsToday` is supplied by the caller (see KDoc). */
    fun validateDailyLimit(runsToday: Int, policy: RunPolicy): List<String> =
        if (policy.dailyRunLimit > 0 && runsToday >= policy.dailyRunLimit) {
            listOf("每日次数上限: 今日已跑 $runsToday 次，本校上限 ${policy.dailyRunLimit} 次")
        } else {
            emptyList()
        }
}
