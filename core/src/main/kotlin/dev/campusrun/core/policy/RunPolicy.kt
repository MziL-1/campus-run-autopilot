package dev.campusrun.core.policy

/** A WGS-84-ish point as delivered by the run API (`lat` + `lng`/`lon`). */
data class LatLng(val lat: Double, val lng: Double)

/**
 * Server-advertised rules for one run, flattened from the 7.4.x `runModePolicy` and
 * `getGeoFenceForRun` business payloads (see [PolicyClient]).
 *
 * The 7.4.x rule body only carries what it carries; fields it cannot express are left at
 * their "not advertised" zero/empty value rather than invented:
 *
 * - [requiredPoints] / [optionalPoints] come from a fence's `passPoints` split by
 *   `isFixed` (`isFixed == 0` is an optional/free point, anything else required). The
 *   live 7.4.x fence entity models only the polygon, so a school whose fence has no
 *   `passPoints` yields empty lists (the five-point API is the live checkpoint source,
 *   but it needs the configured host and its own body signature, outside this interface).
 * - [fence] is the polygon (`points`) of the first fence that has points; the model holds
 *   a single polygon, so multiple configured fences are not representable.
 * - [paceMinSecPerKm] / [paceMaxSecPerKm] are `speedTop` / `speedBottom` (minutes per km
 *   upstream) converted to seconds.
 * - [validFromHour] / [validToHour] are the first `validTime` entry only; a school with
 *   split windows (e.g. a lunch break) cannot be represented by this model.
 * - [maxDistancePerRunM] is `dayGoal`, the only distance ceiling the 7.4.x rule exposes
 *   (upstream labels it 单日上限). `minDistance` is the per-run floor and has no field
 *   here.
 * - [dailyRunLimit] is 0: 7.4.x advertises only `weekCountGoal` (weekly), which is not a
 *   daily cap. 0 means "not advertised", not "zero runs allowed".
 * - [faceWindow] is an epoch-millisecond window during which submissions must be avoided
 *   (spec R6). The 7.4.x `runFaceCheckConfigDTO` has no window/start/end fields (only
 *   `enable`, `checkPointMin/Max`, `checkRunValidTime`, `checkLength`, `checkValidTime`,
 *   `type`), and it was `null` in the live capture, so the parser only sets it when an
 *   explicit window is present; otherwise it stays `null` and the gate must not invent
 *   one.
 * - An empty policy (no `runRuleModel`, 7.4.x outside 10:00-22:00) is represented by
 *   zero/empty fields — [parseRunPolicy] treats it as valid, not as an error.
 */
data class RunPolicy(
    val requiredPoints: List<LatLng>,
    val optionalPoints: List<LatLng>,
    val fence: List<LatLng>,
    val paceMinSecPerKm: Int,
    val paceMaxSecPerKm: Int,
    val validFromHour: Int,
    val validToHour: Int,
    val maxDistancePerRunM: Int,
    val dailyRunLimit: Int,
    val faceWindow: ClosedRange<Long>?,
    /**
     * `runRuleModel.minDistance` — the per-run distance floor (Task 9 gate input; plan
     * amendment recorded in the Task 9 dispatch). 0 means "not advertised" (empty rule),
     * which [dev.campusrun.core.policy.RunPolicyValidator] treats as the settlement
     * completeness floor instead (upstream builder `selDistance = 2000`).
     */
    val minDistancePerRunM: Int = 0,
    /**
     * `runRuleModel.weekCountGoal` — the school's **weekly** run-count goal (7.4.x
     * advertises this instead of any daily cap). 0 means "not advertised". Kept
     * separate from [dailyRunLimit] (which stays 0: weekly ≠ daily) so the Home can
     * surface the count the school actually requires.
     */
    val weekCountGoal: Int = 0,
)
