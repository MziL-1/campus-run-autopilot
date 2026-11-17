package dev.campusrun.core.track

import dev.campusrun.core.policy.LatLng
import dev.campusrun.core.policy.RunPolicy
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** One GPS sample of a synthesized run (spec C2 contract; Task 8's uploader consumes it). */
data class TrackPoint(
    val lat: Double,
    val lng: Double,
    val elapsedSec: Int,
    val speedMps: Double,
    val stepRateHz: Double,
)

/**
 * Synthesizes a plausible, risk-controlled run track from a [RunPolicy] (spec C2, §5.5):
 *
 * - **Route** — a seeded wavy ellipse loop inscribed in the fence polygon. A slot sitting
 *   nearest a required point's angle is pinned to that point, so the path passes exactly
 *   through every required point. The loop scale is bisected until the *emitted* path
 *   length hits [targetDistanceM] (3 % bound) while every vertex and edge sample stays
 *   inside the fence ([Geo.insidePolygon], boundary inclusive); when one loop is shorter
 *   than the target the lap is repeated (runners do laps too).
 * - **Pacing** — a seeded multi-frequency speed profile (peak deviation 6–12 % around
 *   the mean) normalized to traverse the whole route in [durationSec]: mean pace is
 *   `1000 * durationSec / targetDistanceM` and instantaneous pace stays inside the
 *   policy window — natural variation, never a constant speed (§5.5-1).
 * - **Cadence** — speed-coupled wobble around ~166 steps/min, clamped inside spec C2's
 *   70–240 步频 band.
 * - **Determinism** — one [Random] stream per seed consumed in a fixed order: the same
 *   seed yields a byte-identical track, different seeds differ in route shape *and*
 *   speed profile (§5.5-2 anti-association between same-school users).
 *
 * The fence must be star-shaped around its bounding-box centre (every convex fence, e.g.
 * a rectangular campus area, qualifies) and must contain each required point; geometry
 * that makes the contract impossible is rejected with [IllegalArgumentException].
 */
object TrackSynthesizer {

    /** Loop slots: one every 5° of the ellipse; required points pin their nearest slot. */
    private const val SLOT_COUNT = 72

    private const val VALIDITY_BISECTIONS = 30
    private const val LENGTH_BISECTIONS = 32
    private const val MAX_LAPS = 8

    /** Interior edge samples every ~8 m so concave notches cannot hide a chord exit. */
    private const val EDGE_SAMPLE_M = 8.0

    /** Ceiling for the loop scale search; the fence boundary stops it well below this. */
    private const val SCALE_CEILING = 1.5

    /** Nonzero floor for the collapsed loop so a zero-length route cannot be emitted. */
    private const val SCALE_FLOOR = 1e-6

    fun synthesize(policy: RunPolicy, targetDistanceM: Int, durationSec: Int, seed: Long): List<TrackPoint> {
        require(targetDistanceM > 0) { "targetDistanceM must be positive, got $targetDistanceM" }
        require(durationSec > 0) { "durationSec must be positive, got $durationSec" }
        val fence = policy.fence
        require(fence.size >= 3) { "fence must be a polygon of at least 3 points, got ${fence.size}" }
        require(policy.requiredPoints.size <= SLOT_COUNT) {
            "required points (${policy.requiredPoints.size}) exceed loop capacity ($SLOT_COUNT)"
        }
        policy.requiredPoints.forEach { p ->
            require(Geo.insidePolygon(p, fence)) { "required point $p lies outside the fence" }
        }
        if (policy.maxDistancePerRunM > 0) {
            require(targetDistanceM <= policy.maxDistancePerRunM) {
                "target $targetDistanceM m exceeds the policy cap ${policy.maxDistancePerRunM} m"
            }
        }
        if (policy.paceMinSecPerKm > 0 && policy.paceMaxSecPerKm > 0) {
            val meanPace = 1000.0 * durationSec / targetDistanceM
            require(meanPace in policy.paceMinSecPerKm.toDouble()..policy.paceMaxSecPerKm.toDouble()) {
                "target/duration mean pace $meanPace s/km is outside policy window " +
                    "[${policy.paceMinSecPerKm}, ${policy.paceMaxSecPerKm}] s/km"
            }
        }

        val rng = Random(seed)

        // Local metric frame around the fence's bounding-box centre.
        val latMin = fence.minOf { it.lat }
        val latMax = fence.maxOf { it.lat }
        val lngMin = fence.minOf { it.lng }
        val lngMax = fence.maxOf { it.lng }
        val originLat = (latMin + latMax) / 2
        val originLng = (lngMin + lngMax) / 2
        val mPerLat = Geo.distanceM(LatLng(originLat, originLng), LatLng(originLat + 1.0, originLng))
        val mPerLng = Geo.distanceM(LatLng(originLat, originLng), LatLng(originLat, originLng + 1.0))
        val frame = Frame(originLat, originLng, mPerLat, mPerLng)
        val halfWidthM = (lngMax - lngMin) / 2 * mPerLng
        val halfHeightM = (latMax - latMin) / 2 * mPerLat

        // Seeded wavy ellipse loop (radial lobes 1/3/5/7 give each seed its own shape).
        val theta0 = rng.nextDouble() * 2 * PI
        val lobe1 = rng.nextDouble() * 0.05
        val lobe3 = 0.03 + rng.nextDouble() * 0.045
        val lobe5 = 0.03 + rng.nextDouble() * 0.045
        val lobe7 = 0.02 + rng.nextDouble() * 0.04
        val phase1 = rng.nextDouble() * 2 * PI
        val phase3 = rng.nextDouble() * 2 * PI
        val phase5 = rng.nextDouble() * 2 * PI
        val phase7 = rng.nextDouble() * 2 * PI
        val slotAngles = DoubleArray(SLOT_COUNT) { theta0 + 2 * PI * it / SLOT_COUNT }
        val baseSlots = Array(SLOT_COUNT) { i ->
            val th = slotAngles[i]
            val r = 1.0 +
                lobe1 * sin(th + phase1) +
                lobe3 * sin(3 * th + phase3) +
                lobe5 * sin(5 * th + phase5) +
                lobe7 * sin(7 * th + phase7)
            Vec2(halfWidthM * 0.99 * cos(th) * r, halfHeightM * 0.99 * sin(th) * r)
        }

        // Pin each required point to its nearest free slot: the route passes through it.
        val pinnedSlots = arrayOfNulls<Vec2>(SLOT_COUNT)
        policy.requiredPoints.forEach { required ->
            val local = frame.toLocal(required)
            val wanted = atan2(local.y, local.x)
            var best = -1
            var bestDelta = Double.MAX_VALUE
            for (i in 0 until SLOT_COUNT) {
                if (pinnedSlots[i] != null) continue
                val delta = abs(angularDistance(slotAngles[i], wanted))
                if (delta < bestDelta) {
                    bestDelta = delta
                    best = i
                }
            }
            pinnedSlots[best] = local
        }

        fun singleLapAt(scale: Double): List<Vec2> =
            buildRoute(pinnedSlots, baseSlots, scale, laps = 1)

        fun validAt(scale: Double): Boolean = validRoute(singleLapAt(scale), frame, fence)

        // Largest loop scale whose vertices and edge samples all stay inside the fence.
        var scaleLo = 0.0
        var scaleHi = SCALE_CEILING
        if (validAt(scaleHi)) {
            scaleLo = scaleHi
        } else {
            require(validAt(scaleLo)) { "fence centre or required-point spokes fall outside the fence" }
            repeat(VALIDITY_BISECTIONS) {
                val mid = (scaleLo + scaleHi) / 2
                if (validAt(mid)) scaleLo = mid else scaleHi = mid
            }
        }
        val maxScale = scaleLo

        // One lap may be too short for the target — repeat it (a lap-around-the-track run).
        var laps = 1
        val longestLoopM = polylineLength(singleLapAt(maxScale))
        while (laps < MAX_LAPS && longestLoopM * laps < targetDistanceM * 1.05) laps++
        require(longestLoopM * laps >= targetDistanceM * 1.05) {
            "fence cannot contain $targetDistanceM m: largest in-fence loop is " +
                "${longestLoopM * MAX_LAPS} m over $MAX_LAPS laps"
        }

        // Seeded speed profile: surge/settle around the mean, peak deviation 6–12 %.
        val speedFactors = DoubleArray(durationSec)
        val cadenceWobble = DoubleArray(durationSec)
        run {
            val cyclesSlow = 1.0 + rng.nextDouble() * 1.5
            val cyclesMid = 4.0 + rng.nextDouble() * 3.0
            val cyclesFast = 9.0 + rng.nextDouble() * 5.0
            val ampSlow = 0.03 + rng.nextDouble() * 0.03
            val ampMid = 0.02 + rng.nextDouble() * 0.03
            val ampFast = 0.01 + rng.nextDouble() * 0.02
            val phSlow = rng.nextDouble() * 2 * PI
            val phMid = rng.nextDouble() * 2 * PI
            val phFast = rng.nextDouble() * 2 * PI
            val peakDeviation = 0.06 + rng.nextDouble() * 0.06
            var maxDev = 0.0
            for (t in 0 until durationSec) {
                val x = (t + 0.5) / durationSec
                val dev = ampSlow * sin(2 * PI * cyclesSlow * x + phSlow) +
                    ampMid * sin(2 * PI * cyclesMid * x + phMid) +
                    ampFast * sin(2 * PI * cyclesFast * x + phFast)
                speedFactors[t] = dev
                maxDev = max(maxDev, abs(dev))
            }
            val devScale = peakDeviation / max(maxDev, 1e-12)
            for (t in 0 until durationSec) speedFactors[t] = 1.0 + speedFactors[t] * devScale
            val phCad1 = rng.nextDouble() * 2 * PI
            val phCad2 = rng.nextDouble() * 2 * PI
            for (t in 0 until durationSec) {
                val x = (t + 0.5) / durationSec
                cadenceWobble[t] = 6.0 * sin(2 * PI * 2.3 * x + phCad1) +
                    4.0 * sin(2 * PI * 6.1 * x + phCad2)
            }
        }

        fun routeAt(scale: Double): List<Vec2> = buildRoute(pinnedSlots, baseSlots, scale, laps)

        fun emittedLengthAt(scale: Double): Double =
            emittedLengthM(frame, routeAt(scale), speedFactors, cadenceWobble, durationSec)

        // Bracket the length root: collapsed route shorter than target, max loop longer.
        require(emittedLengthAt(SCALE_FLOOR) < targetDistanceM) {
            "target $targetDistanceM m is below the minimum detour length " +
                "(${emittedLengthAt(SCALE_FLOOR)} m with $laps laps)"
        }
        require(emittedLengthAt(maxScale) >= targetDistanceM) {
            "largest in-fence loop emits only ${emittedLengthAt(maxScale)} m for a $targetDistanceM m target"
        }

        // Bisect the loop scale; invariant: valid(lenLo) && len(lenLo) < target <= len(lenHi).
        var lenLo = SCALE_FLOOR
        var lenHi = maxScale
        repeat(LENGTH_BISECTIONS) {
            val mid = (lenLo + lenHi) / 2
            if (validAt(mid) && emittedLengthAt(mid) < targetDistanceM) {
                lenLo = mid
            } else {
                lenHi = mid
            }
        }

        val track = emit(frame, routeAt(lenLo), speedFactors, cadenceWobble, durationSec)
        val finalLengthM = Geo.pathLengthM(track.map { LatLng(it.lat, it.lng) })
        require(abs(finalLengthM - targetDistanceM) / targetDistanceM <= 0.03) {
            "synthesized path length $finalLengthM m is not within 3% of $targetDistanceM m"
        }
        return track
    }

    /** Closes the loop: [laps] cycles of the slots, then the first slot again. */
    private fun buildRoute(
        pinned: Array<Vec2?>,
        base: Array<Vec2>,
        scale: Double,
        laps: Int,
    ): List<Vec2> {
        val route = ArrayList<Vec2>(base.size * laps + 1)
        repeat(laps) {
            for (i in base.indices) route += pinned[i] ?: Vec2(base[i].x * scale, base[i].y * scale)
        }
        route += pinned[0] ?: Vec2(base[0].x * scale, base[0].y * scale)
        return route
    }

    /** True when every vertex and every ~8 m edge sample of [route] is inside [fence]. */
    private fun validRoute(route: List<Vec2>, frame: Frame, fence: List<LatLng>): Boolean {
        for (v in route) {
            if (!Geo.insidePolygon(frame.toGlobal(v), fence)) return false
        }
        for (i in 1 until route.size) {
            val a = route[i - 1]
            val b = route[i]
            val length = a.distanceTo(b)
            val steps = max(2, ceil(length / EDGE_SAMPLE_M).toInt())
            for (s in 1 until steps) {
                val t = s.toDouble() / steps
                val sample = Vec2(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
                if (!Geo.insidePolygon(frame.toGlobal(sample), fence)) return false
            }
        }
        return true
    }

    private fun polylineLength(route: List<Vec2>): Double {
        var total = 0.0
        for (i in 1 until route.size) total += route[i - 1].distanceTo(route[i])
        return total
    }

    /**
     * Walks [route] at 1 Hz over [durationSec]: positions from arc-length lookup,
     * speed from the normalized speed profile (whole route covered exactly once),
     * cadence from speed plus a seeded wobble, clamped to spec C2's 70–240 band.
     */
    private fun emit(
        frame: Frame,
        route: List<Vec2>,
        speedFactors: DoubleArray,
        cadenceWobble: DoubleArray,
        durationSec: Int,
    ): List<TrackPoint> {
        val cumulative = DoubleArray(route.size)
        for (i in 1 until route.size) {
            cumulative[i] = cumulative[i - 1] + route[i - 1].distanceTo(route[i])
        }
        val routeLength = cumulative.last()
        require(routeLength > 0.0) { "route has zero length" }

        var sumFactors = 0.0
        for (f in speedFactors) sumFactors += f
        val speedScale = routeLength / sumFactors
        val meanSpeedMps = routeLength / durationSec

        fun sample(elapsed: Int, arc: Double, speedMps: Double): TrackPoint {
            val global = frame.toGlobal(pointAt(route, cumulative, arc))
            val cadence = (
                166.0 + 45.0 * (speedMps / meanSpeedMps - 1.0) +
                    cadenceWobble[minOf(elapsed, cadenceWobble.size - 1)]
                ).coerceIn(110.0, 200.0)
            return TrackPoint(global.lat, global.lng, elapsed, speedMps, cadence)
        }

        val points = ArrayList<TrackPoint>(durationSec + 1)
        points += sample(0, 0.0, speedScale * speedFactors[0])
        var arc = 0.0
        for (t in 1..durationSec) {
            arc += speedScale * speedFactors[t - 1]
            val speed = if (t < durationSec) {
                speedScale * speedFactors[t]
            } else {
                speedScale * speedFactors[durationSec - 1]
            }
            points += sample(t, arc, speed)
        }
        return points
    }

    /** The point at [arc] metres along the polyline (clamped to its ends). */
    private fun pointAt(route: List<Vec2>, cumulative: DoubleArray, arc: Double): Vec2 {
        val s = arc.coerceIn(0.0, cumulative.last())
        var lo = 0
        var hi = cumulative.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (cumulative[mid] < s) lo = mid + 1 else hi = mid
        }
        if (lo == 0) return route[0]
        val span = cumulative[lo] - cumulative[lo - 1]
        if (span <= 0.0) return route[lo]
        val t = (s - cumulative[lo - 1]) / span
        val a = route[lo - 1]
        val b = route[lo]
        return Vec2(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
    }

    private fun emittedLengthM(
        frame: Frame,
        route: List<Vec2>,
        speedFactors: DoubleArray,
        cadenceWobble: DoubleArray,
        durationSec: Int,
    ): Double = Geo.pathLengthM(
        emit(frame, route, speedFactors, cadenceWobble, durationSec).map { LatLng(it.lat, it.lng) },
    )

    /** Wrapped angular difference in (−π, π]. */
    private fun angularDistance(a: Double, b: Double): Double {
        var delta = (a - b) % (2 * PI)
        if (delta > PI) delta -= 2 * PI
        if (delta < -PI) delta += 2 * PI
        return delta
    }
}

/** A point in the local metre frame (x = east, y = north). */
private data class Vec2(val x: Double, val y: Double) {
    fun distanceTo(other: Vec2): Double {
        val dx = x - other.x
        val dy = y - other.y
        return sqrt(dx * dx + dy * dy)
    }
}

/** Equirectangular metres frame around the fence's bounding-box centre. */
private class Frame(
    private val originLat: Double,
    private val originLng: Double,
    private val mPerLat: Double,
    private val mPerLng: Double,
) {
    fun toLocal(p: LatLng): Vec2 = Vec2((p.lng - originLng) * mPerLng, (p.lat - originLat) * mPerLat)

    fun toGlobal(v: Vec2): LatLng = LatLng(originLat + v.y / mPerLat, originLng + v.x / mPerLng)
}
