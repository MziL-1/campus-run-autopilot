package dev.campusrun.core.track

import dev.campusrun.core.policy.LatLng
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geometry primitives for the track synthesizer (spec C2: the emitted path must be a
 * legal in-fence path and the required points must be passed).
 */
object Geo {

    /** Mean Earth radius in metres, used by the haversine [distanceM]. */
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Tolerance for the on-boundary test in degrees (~0.1 m at the equator). */
    private const val ON_EDGE_EPS_DEG = 1e-6

    /** Great-circle distance between [a] and [b] in metres (haversine). */
    fun distanceM(a: LatLng, b: LatLng): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = lat2 - lat1
        val dLng = Math.toRadians(b.lng - a.lng)
        val sinLat = sin(dLat / 2)
        val sinLng = sin(dLng / 2)
        val h = sinLat * sinLat + cos(lat1) * cos(lat2) * sinLng * sinLng
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /**
     * Point-in-polygon by ray casting.
     *
     * Boundary rule (documented choice): the fence line itself counts as **inside** — a
     * point on an edge (within [ON_EDGE_EPS_DEG]) is a legal fence position, matching
     * spec C2 where the run area includes its own boundary.
     */
    fun insidePolygon(p: LatLng, polygon: List<LatLng>): Boolean {
        if (polygon.size < 3) return false
        for (i in polygon.indices) {
            if (onSegment(p, polygon[i], polygon[(i + 1) % polygon.size])) return true
        }
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val pi = polygon[i]
            val pj = polygon[j]
            if ((pi.lat > p.lat) != (pj.lat > p.lat)) {
                val edgeLng = (pj.lng - pi.lng) * (p.lat - pi.lat) / (pj.lat - pi.lat) + pi.lng
                if (p.lng < edgeLng) inside = !inside
            }
            j = i
        }
        return inside
    }

    /** Total length of the polyline [points] in metres. */
    fun pathLengthM(points: List<LatLng>): Double {
        var total = 0.0
        for (i in 1 until points.size) total += distanceM(points[i - 1], points[i])
        return total
    }

    /** True when [p] lies on segment [a]-[b] (inclusive, planar degrees + epsilon). */
    private fun onSegment(p: LatLng, a: LatLng, b: LatLng): Boolean {
        if (p.lat < minOf(a.lat, b.lat) - ON_EDGE_EPS_DEG) return false
        if (p.lat > maxOf(a.lat, b.lat) + ON_EDGE_EPS_DEG) return false
        if (p.lng < minOf(a.lng, b.lng) - ON_EDGE_EPS_DEG) return false
        if (p.lng > maxOf(a.lng, b.lng) + ON_EDGE_EPS_DEG) return false
        val edgeLng = b.lng - a.lng
        val edgeLat = b.lat - a.lat
        val cross = edgeLng * (p.lat - a.lat) - edgeLat * (p.lng - a.lng)
        val edgeLen = sqrt(edgeLng * edgeLng + edgeLat * edgeLat)
        return abs(cross) <= ON_EDGE_EPS_DEG * edgeLen
    }
}
