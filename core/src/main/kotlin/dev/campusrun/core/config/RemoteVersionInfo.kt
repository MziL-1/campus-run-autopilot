package dev.campusrun.core.config

import kotlinx.serialization.Serializable

/**
 * The `version.json` document of the update channel — spec §5.6's pinned field list:
 * "最低 APK 版本、配置版本、协议参数版本、下载多镜像 URL" (§8-6: the schema is builder-defined,
 * these four are mandated).
 *
 * The repo sample at `config/version.json` is this schema's executable documentation and
 * is pinned by `UpdateCheckerTest`. It is data only: no scripts, no dynamic code — the
 * one logical-level change it can express is [minApkVersionCode], which the app turns
 * into a "new APK required" prompt via [UpdateChecker.updateRequired] (spec §5.6 热更边界).
 *
 * @param minApkVersionCode lowest `versionCode` of an APK that may still run against the
 *   current remote config; below it the app must prompt for a new APK.
 * @param configVersion version of the remote configuration document set.
 * @param protocolParamsVersion the [ProtocolParams.versionTag] the remote config now
 *   serves; higher than the bundled one means a hot params refresh is available.
 * @param apkMirrors ordered download URLs for the APK, tried like the `version.json`
 *   mirrors themselves (first success wins).
 */
@Serializable
data class RemoteVersionInfo(
    val minApkVersionCode: Int,
    val configVersion: Int,
    val protocolParamsVersion: Int,
    val apkMirrors: List<String>,
)
