package dev.campusrun.autopilot

import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.campusrun.app.data.AndroidParamsKeyStore
import dev.campusrun.app.data.AndroidTokenStore
import dev.campusrun.app.data.DeviceIdentitySnapshot
import dev.campusrun.app.diag.DiagnosticsScreen
import dev.campusrun.app.home.CoreHomeData
import dev.campusrun.app.home.HomeScreen
import dev.campusrun.app.home.HomeViewModel
import dev.campusrun.app.login.ApiAuthenticator
import dev.campusrun.app.login.LoginScreen
import dev.campusrun.app.login.LoginViewModel
import dev.campusrun.app.run.CoreRunStages
import dev.campusrun.app.run.RunScreen
import dev.campusrun.app.run.RunViewModel
import dev.campusrun.core.api.ApiClient
import dev.campusrun.core.config.ParamsStore
import dev.campusrun.core.config.ProtocolParams
import dev.campusrun.core.config.RemoteVersionInfo
import dev.campusrun.core.config.UpdateChecker
import dev.campusrun.core.diag.RedactedLog
import dev.campusrun.core.identity.DeviceIdentity
import dev.campusrun.core.session.SessionManager
import dev.campusrun.core.session.Token
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Response
import java.net.InetAddress

/**
 * Wiring host for the full product flow (T12 login graph + T13 screens): builds the object
 * graph (encrypted stores → params → ApiClient → SessionManager → Authenticator →
 * ViewModels), restores a persisted session on cold start, runs the startup update check,
 * and hosts 登录 → 首页 → 一键跑 → 进度/历史 → 导出诊断 behind one Compose `Route` state.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Debug builds only: chrome://inspect access to the captcha WebView (T12 diagnosis).
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        }

        // Encrypted per-install stores (spec C7): session + device identity + wire keys.
        val tokenStore = AndroidTokenStore(this)
        val identity = resolveDeviceIdentity(tokenStore)

        // Bundled wire params overlaid with per-install keys (spec §5.6 hot-update seam;
        // the update channel itself lands in a later task).
        val paramsStore = ParamsStore(
            bundled = productionParams(),
            keyStore = AndroidParamsKeyStore(this),
            log = { message -> Log.w(TAG, message) },
        )
        val deviceModel = asciiHeader(Build.MODEL, "Android Device")
        val osVersion = asciiHeader(Build.VERSION.RELEASE, "13")
        // Diagnostics seam (T12 device run): status line only — method/path/code/content-
        // type/body-length. Never headers or bodies (Authorization, phone, token).
        val eventListener = object : EventListener() {
            override fun dnsEnd(call: Call, domainName: String, inetAddresses: List<InetAddress>) {
                Log.i(TAG, "dns $domainName -> ${inetAddresses.joinToString { it.hostAddress ?: "?" }}")
            }

            override fun connectionAcquired(call: Call, connection: Connection) {
                Log.i(TAG, "conn ${connection.socket().inetAddress?.hostAddress} proto=${connection.protocol()}")
            }

            override fun responseHeadersEnd(call: Call, response: Response) {
                Log.i(
                    TAG,
                    "resp ${response.code} proto=${response.protocol} " +
                        "tls=${response.handshake?.tlsVersion} ${response.handshake?.cipherSuite} " +
                        "${call.request().url.encodedPath}",
                )
            }
        }
        val httpClient = OkHttpClient.Builder()
            .eventListener(eventListener)
            .addInterceptor { chain ->
                val request = chain.request()
                Log.i(TAG, "net> ${request.method} ${request.url.encodedPath}")
                val response = chain.proceed(request)
                val peer = chain.connection()?.socket()?.inetAddress?.hostAddress ?: "?"
                // peekBody is non-consuming; length only, never the content.
                val len = runCatching { response.peekBody(MAX_LOGGED_BODY_BYTES).string().length }
                    .getOrDefault(-1)
                Log.i(
                    TAG,
                    "net< ${response.code} ${request.url.encodedPath} peer=$peer " +
                        "proto=${chain.connection()?.protocol()} type=${response.header("Content-Type")} peekLen=$len",
                )
                response
            }
            .build()
        val api = ApiClient(
            params = paramsStore.current(),
            deviceHeaders = deviceHeaders(identity.stableId, deviceModel, osVersion),
            client = httpClient,
        )

        // Cold start: restore applies the persisted token to [api] (or null → login UI).
        val session = SessionManager(api, tokenStore)
        val restored: Token? = session.restore()

        val vm = LoginViewModel(ApiAuthenticator(api, deviceModel = deviceModel, osVersion = osVersion))

        // Diagnostics log (spec §6.3): in-memory, redacted at write time; the export screen
        // hands it to Task 11's DiagnosticExport together with the config version and the
        // recent-run summary.
        val diagLog = RedactedLog()
        diagLog.w("app", "cold start; session=${if (restored != null) "restored" else "absent"}")

        // Startup update check (spec §5.6 / G4 / R5): version.json on a background thread —
        // every mirror failure is logged into diagLog and fails silent to "no update", and
        // nothing here can block startup (ruling). The document feeds
        // HomeViewModel.onVersionCheck, which raises the dismissible prompt only when
        // minApkVersionCode > current.
        val updateInfo = mutableStateOf<RemoteVersionInfo?>(null)
        val updateChecker = UpdateChecker(
            log = { message ->
                diagLog.w("update", message)
                Log.i(TAG, "update> $message")
            },
        )
        Thread(
            {
                val info = updateChecker.check(UPDATE_MIRRORS)
                if (info != null) {
                    diagLog.w(
                        "update",
                        "version document: minApkVersionCode=${info.minApkVersionCode} " +
                            "configVersion=${info.configVersion} " +
                            "protocolParamsVersion=${info.protocolParamsVersion}",
                    )
                }
                updateInfo.value = info
            },
            "update-check",
        ).apply { isDaemon = true }.start()

        // Diagnostic export target: app-specific external files (user-reachable path shown
        // on screen), internal app-specific files as the fallback.
        val diagOutDir = (getExternalFilesDir(null) ?: filesDir).toPath()
        val currentVersionCode = packageManager.getPackageInfo(packageName, 0).let { info ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode
            }
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var tokenState by remember { mutableStateOf(restored) }
                    var route by remember { mutableStateOf(Route.HOME) }
                    val token = tokenState
                    if (token == null) {
                        LoginScreen(vm) { newToken ->
                            // Login success: persist the session and pin the device-identity
                            // snapshot so later hint changes cannot churn the DeviceId header.
                            session.save(newToken)
                            tokenStore.saveDeviceIdentity(identity)
                            tokenState = newToken
                        }
                    } else {
                        // Per-session VMs: Home loads rules/progress, Run owns the one-shot
                        // pipeline (both over the same ApiClient built above).
                        val homeVm = remember(token) {
                            HomeViewModel(CoreHomeData(api), token, currentVersionCode)
                        }
                        val runVm = remember(token) {
                            RunViewModel(
                                stages = CoreRunStages(api, paramsStore.current()),
                                token = token,
                                log = { line ->
                                    diagLog.w("run", line)
                                    Log.i(TAG, "run> $line")
                                },
                            )
                        }
                        LaunchedEffect(updateInfo.value) {
                            updateInfo.value?.let(homeVm::onVersionCheck)
                        }
                        when (route) {
                            Route.HOME -> HomeScreen(
                                vm = homeVm,
                                onRunClick = { route = Route.RUN },
                                onDiagnosticsClick = { route = Route.DIAGNOSTICS },
                            )

                            Route.RUN -> RunScreen(vm = runVm) { route = Route.HOME }

                            Route.DIAGNOSTICS -> DiagnosticsScreen(
                                outDir = diagOutDir,
                                log = diagLog,
                                configVersion = paramsStore.current().versionTag,
                                onBack = { route = Route.HOME },
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * The pinned identity snapshot after the first login, else a freshly derived id from
     * the persisted salt + current hints (same inputs → same id, so the header stays
     * stable even before the snapshot is written).
     */
    private fun resolveDeviceIdentity(store: AndroidTokenStore): DeviceIdentitySnapshot {
        store.loadDeviceIdentity()?.let { return it }
        val hints = mapOf(
            "androidId" to (Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: ""),
            "model" to Build.MODEL.orEmpty(),
            "manufacturer" to Build.MANUFACTURER.orEmpty(),
        )
        val stableId = DeviceIdentity(store.deviceSalt(), hints).stableId()
        return DeviceIdentitySnapshot(stableId, hints)
    }

    /** Upstream `DEFAULT_DEVICE_CONFIG` header set filled with this device's real values. */
    @Suppress("DEPRECATION")
    private fun deviceHeaders(deviceId: String, deviceModel: String, osVersion: String): Map<String, String> {
        val real = android.graphics.Point().also { windowManager.defaultDisplay.getRealSize(it) }
        val dm = resources.displayMetrics
        val logicW = (real.x / dm.density + 0.5f).toInt()
        val logicH = (real.y / dm.density + 0.5f).toInt()
        return mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "appVersion" to "7.3.92",
            "physicPixel" to "${real.x}x${real.y}",
            "logicPixel" to "${logicW}x${logicH}",
            "isRoot" to "0",
            "osType" to "0",
            "cpuModel" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"),
            "deviceName" to deviceModel,
            "DeviceId" to deviceId,
            "osVersion" to osVersion,
            "User-Agent" to "okhttp/4.12.0",
        )
    }

    companion object {
        private const val TAG = "CampusRun"
        private const val MAX_LOGGED_BODY_BYTES = 4096L

        /**
         * Startup `version.json` mirror roots (spec C5 / R5): **jsDelivr over GitHub is the
         * production default** (国内可达; raw.githubusercontent is unstable on the mainland),
         * with raw GitHub as the ordered fallback — `UpdateChecker` tries them in order and
         * every failure fails silent to "no update" (logged into the diagnostics log only).
         *
         * `OWNER/REPO` are placeholders until the public repo exists — the committed
         * `config/version.json` sample uses the same placeholder — so today every check
         * 404s into `null`; publishing the repo means replacing exactly this one constant.
         *
         * **Remote params document decision (T10 handoff / ruling 3): deferred.** No
         * `config/params.json` exists on any mirror yet, and [ApiClient] snapshots its
         * `ProtocolParams` at construction — an `applyRemote` after startup would be inert
         * for this process *and* non-persistent (T10: only install keys persist), so
         * wiring it now would be a no-op. When the doc lands, fetch it from these same
         * mirror roots and call [ParamsStore.applyRemote] *before* building [ApiClient]
         * (time-boxed), or teach [ApiClient] to read params lazily. The seam is ready;
         * nothing else in this task changes.
         */
        private val UPDATE_MIRRORS = listOf(
            "https://cdn.jsdelivr.net/gh/OWNER/REPO@main",
            "https://raw.githubusercontent.com/OWNER/REPO/main",
        )

        /**
         * Production wire parameters (upstream `core/config.py`): endpoint, RSA public
         * key, sign salt; `aesKey`/`kdfSalt` are the bundled fallback only — [ParamsStore]
         * overlays per-install random keys (T5 ruling), same forced-determinism note as
         * the CLI's `productionParams`.
         */
        private fun productionParams(): ProtocolParams = ProtocolParams(
            aesKey = "0123456789abcdef".toByteArray(Charsets.US_ASCII),
            kdfSalt = "abcdefghijklmnopqrstuvwxyz0123456789ABCD".toByteArray(Charsets.US_ASCII),
            rsaPublicKey = RSA_PUBLIC_KEY_B64,
            endpointBase = "https://run.gxapp.iydsj.com",
            signSalt = "2slhe02lsfiwowlcixisla_sls-_slaor",
            versionTag = 1,
        )

        /** Upstream `crypto.py REQ_RSA_PKCS1_B64` (public by construction). */
        private val RSA_PUBLIC_KEY_B64 = (
            "MIGJAoGBALmmqVPOwY1mTVHGEfg7jHck1MNXsVtUfrqY99bm/W2cjLi3LvG/wMYw" +
                "bhmf9O+y3CUlZ5g4AMd2Ly4XbWUloG/O8+1USGJ8ddmrzbl8j2EZi0OhpVCYI381" +
                "+zZ0qa3z1cOuG2XdJnE7pO6Y4JNC37miqMXYmjejVi2QDIbONomfAgMBAAE="
            )

        /**
         * Header values must be visible ASCII (OkHttp rejects control and non-ASCII
         * octets; the brief requires ASCII-only headers) — drop anything else, fall back
         * when the result is empty.
         */
        fun asciiHeader(value: String?, fallback: String): String {
            val cleaned = value.orEmpty()
                .filter { it.code in 0x20..0x7E }
                .trim()
            return cleaned.ifEmpty { fallback }
        }
    }
}

/** Single-activity navigation (brief Step 3): the logged-in screens hosted by MainActivity. */
private enum class Route { HOME, RUN, DIAGNOSTICS }
