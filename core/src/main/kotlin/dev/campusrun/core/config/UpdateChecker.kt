package dev.campusrun.core.config

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Update-channel client (spec §5.6 / R5): fetches `version.json` from an ordered mirror
 * list so that "分发与更新在国内可达" holds even when one CDN is blocked or down.
 *
 * Each entry of [check]'s `baseUrls` is a mirror root that hosts the document at
 * [VERSION_PATH] (the repo sample `config/version.json`, synced to the CDN at release
 * time — e.g. a jsDelivr or raw.githubusercontent base). Mirrors are tried **in order,
 * try-until-success**: the first base URL that answers 2xx with a parseable
 * [RemoteVersionInfo] wins; every mirror failure (transport, HTTP status, unparsable
 * body) is written to [log] and skipped — never thrown — and when no mirror produces a
 * document [check] returns `null`, so the caller simply keeps the bundled config.
 * The decoder is forward-tolerant (unknown fields of a future schema version are
 * ignored — spec §5.6 "旧版本 APK 兼容读取"), so schema growth never disables the
 * channel for old builds.
 *
 * No script engine (spec §5.6 热更边界): the document is data only. The single
 * logical-level change it carries, [RemoteVersionInfo.minApkVersionCode], surfaces
 * through [updateRequired] as a "new APK required" prompt — never as loaded code.
 *
 * @param client plain OkHttp client (config documents travel unencrypted from the CDN;
 *   they are public release metadata, unlike the signed protocol envelopes of
 *   [dev.campusrun.core.api.ApiClient]).
 * @param log sink for mirror failures; defaults to stderr, tests capture it.
 */
class UpdateChecker(
    private val client: OkHttpClient = OkHttpClient(),
    private val log: (String) -> Unit = { message -> System.err.println(message) },
) {

    /**
     * Fetches `version.json` from [baseUrls] in order and returns the first parseable
     * document, or `null` when every mirror fails (failures are [log]ged, not thrown).
     */
    fun check(baseUrls: List<String>): RemoteVersionInfo? {
        for (base in baseUrls) {
            val url = base.trimEnd('/') + VERSION_PATH
            val body = try {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (response.isSuccessful) {
                        response.body?.string().orEmpty()
                    } else {
                        log("update: mirror $url answered HTTP ${response.code}")
                        null
                    }
                }
            } catch (e: Exception) {
                // DNS/connect/timeout/malformed base URL — a dead mirror must not abort the check.
                log("update: mirror $url unreachable: $e")
                null
            }
            if (body == null) continue
            try {
                return VERSION_JSON.decodeFromString(RemoteVersionInfo.serializer(), body)
            } catch (e: Exception) {
                log("update: mirror $url served an unparsable version document: $e")
            }
        }
        return null
    }

    /**
     * True when [info] demands a newer APK than the running build: the only logical-level
     * update path (config data cannot change behaviour beyond parameter values, spec §5.6).
     */
    fun updateRequired(info: RemoteVersionInfo, currentApkVersionCode: Int): Boolean =
        info.minApkVersionCode > currentApkVersionCode

    private companion object {
        /** `config/version.json` relative to each mirror root (repo sample lives there). */
        const val VERSION_PATH = "/config/version.json"

        /**
         * Forward-tolerant decoder: spec §5.6 "配置 schema 版本化，旧版本 APK 兼容读取" —
         * a field added by a future schema version must not make an old build's [check]
         * fail to parse (that would silently stop the update channel from ever telling
         * those old installs about `minApkVersionCode`).
         */
        val VERSION_JSON = Json { ignoreUnknownKeys = true }
    }
}
