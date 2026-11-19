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
import androidx.compose.ui.Modifier
import dev.campusrun.app.data.AndroidParamsKeyStore
import dev.campusrun.app.data.AndroidTokenStore
import dev.campusrun.app.data.DeviceIdentitySnapshot
import dev.campusrun.app.login.ApiAuthenticator
import dev.campusrun.app.login.LoggedInScreen
import dev.campusrun.app.login.LoginScreen
import dev.campusrun.app.login.LoginViewModel
import dev.campusrun.core.api.ApiClient
import dev.campusrun.core.config.ParamsStore
import dev.campusrun.core.config.ProtocolParams
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
 * Wiring host for Task 12: builds the object graph (encrypted stores → params →
 * ApiClient → SessionManager → Authenticator → LoginViewModel), restores a persisted
 * session on cold start, and hosts [LoginScreen] until login succeeds.
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

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val existing = restored
                    if (existing != null) {
                        LoggedInScreen(existing)
                    } else {
                        LoginScreen(vm) { token ->
                            // Login success: persist the session and pin the device-identity
                            // snapshot so later hint changes cannot churn the DeviceId header.
                            // LoginScreen shows the logged-in placeholder from its own state.
                            session.save(token)
                            tokenStore.saveDeviceIdentity(identity)
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
