package dev.campusrun.core.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom

/**
 * Per-install wire-key persistence seam (T5 review ruling carried into Task 10).
 *
 * [ParamsStore] generates the two values that must never come from remote config —
 * `aesKey` (16 bytes) and `kdfSalt` (40 bytes) — once per install and persists them
 * through this interface, conceptually alongside the DeviceIdentity salt (spec §5.5-3:
 * persisted, never churned). The blob layout is raw `aesKey || kdfSalt` (56 bytes).
 *
 * The Android implementation (`EncryptedSharedPreferences`-backed) lands in Task 12;
 * [FileParamsKeyStore] is the file-based JVM implementation for tests and other plain-JVM
 * consumers. Any failure from [load] or [save] means "persistence unavailable" and the
 * store falls back to the bundled params — first-run generation must never brick a fresh
 * install.
 */
interface ParamsKeyStore {

    /** The persisted 56-byte blob, or `null` on a fresh install. May throw when storage is unavailable. */
    fun load(): ByteArray?

    /** Persists the 56-byte blob. Throwing signals "persistence unavailable" to [ParamsStore]. */
    fun save(keys: ByteArray)
}

/**
 * File-backed [ParamsKeyStore] for JVM tests and plain-JVM consumers: a single file
 * holding the raw 56-byte `aesKey || kdfSalt` blob (Task 12 replaces this with the
 * Android secure-storage implementation).
 */
class FileParamsKeyStore(private val file: File) : ParamsKeyStore {

    override fun load(): ByteArray? = if (file.isFile) file.readBytes() else null

    override fun save(keys: ByteArray) {
        file.parentFile?.mkdirs()
        file.writeBytes(keys)
    }
}

/**
 * Hot-reloadable protocol parameter store (spec §5.6 远程热更覆盖).
 *
 * Three rules this class encodes, from the T5 ruling and spec §5.6:
 *
 * 1. **Per-install random keys.** First [current] call generates a fresh random
 *    `aesKey` (AES-128, 16 bytes — same length as `productionParams()`) and `kdfSalt`
 *    (40 bytes = four upstream 10-byte `keyData` segments) via [SecureRandom], persisted
 *    through the [ParamsKeyStore] seam, and reloaded by every later store sharing that
 *    seam (deterministic per persistence). Rationale: upstream generates these per
 *    request anyway (`NetSecClient.encrypt_request`), so any valid-length key is
 *    wire-compatible, while one pinned key shared by all installs would correlate every
 *    request (T3 watch item). When persistence is unavailable, the bundled values remain
 *    the fallback — a fresh install always gets working params.
 * 2. **Remote config never ships keys.** [applyRemote] parses a remote params document
 *    that deliberately has *no* `aesKey`/`kdfSalt` fields: unknown keys are ignored
 *    whatever shape they arrive in, so the merge — restricted to hot-updatable non-key
 *    fields (`versionTag`, `endpointBase`, `signSalt`, `rsaPublicKey`) — can never
 *    overwrite the install keys, and [current] additionally overlays the persisted keys
 *    on every effective params instance.
 * 3. **Numeric version comparison, no script engine.** "Remote newer" means
 *    `remote.versionTag > current.versionTag` — plain field comparison, never data-class
 *    equality (ProtocolParams' ByteArray fields make `equals` identity-based). Logical
 *    level changes do not arrive here at all: they surface through
 *    [RemoteVersionInfo.minApkVersionCode] → [UpdateChecker.updateRequired] as "new APK
 *    required" (spec §5.6 热更边界: config data only, no dynamic code).
 *
 * Remote-applied params live for the process; callers re-apply the fetched document on
 * each startup (the update-channel flow), while the install keys persist across runs.
 */
class ParamsStore(
    val bundled: ProtocolParams,
    private val keyStore: ParamsKeyStore,
) {

    /** Hot-merged params (keys always overlaid from persistence/bundled), `null` before the first [applyRemote]. */
    @Volatile
    private var applied: ProtocolParams? = null

    /** Resolved install keys, memoised so one instance generates at most once. */
    @Volatile
    private var cachedKeys: Pair<ByteArray, ByteArray>? = null

    /**
     * The effective params: hot-updated fields from the last successful [applyRemote]
     * over [bundled], with the per-install `aesKey`/`kdfSalt` always overlaid from the
     * persistence seam (bundled fallback when storage is unavailable).
     */
    fun current(): ProtocolParams {
        val (aesKey, kdfSalt) = installKeys()
        return (applied ?: bundled).copy(aesKey = aesKey, kdfSalt = kdfSalt)
    }

    /**
     * Merges a remote params document over the current effective params — the hot-reload
     * path ("without restart"). The document must carry an integer `versionTag` strictly
     * greater than the effective one; otherwise (equal/older/malformed) nothing changes
     * and `false` is returned. Only non-key fields the document carries are merged;
     * unmentioned fields keep their current value, and `aesKey`/`kdfSalt` — see rule 2 —
     * are never read from the document.
     */
    fun applyRemote(json: String): Boolean {
        val remote = runCatching { REMOTE_JSON.decodeFromString(RemoteParams.serializer(), json) }
            .getOrNull() ?: return false
        val base = applied ?: bundled
        // Compare fields, not instances (ProtocolParams ByteArray equality is identity-based).
        if (remote.versionTag <= base.versionTag) return false
        applied = base.copy(
            versionTag = remote.versionTag,
            endpointBase = remote.endpointBase ?: base.endpointBase,
            signSalt = remote.signSalt ?: base.signSalt,
            rsaPublicKey = remote.rsaPublicKey ?: base.rsaPublicKey,
        )
        return true
    }

    private fun installKeys(): Pair<ByteArray, ByteArray> =
        cachedKeys ?: synchronized(this) { cachedKeys ?: initialiseKeys().also { cachedKeys = it } }

    private fun initialiseKeys(): Pair<ByteArray, ByteArray> {
        val persisted = runCatching { keyStore.load() }.getOrNull()
        if (persisted != null && persisted.size == BLOB_BYTES) {
            return persisted.copyOfRange(0, AES_KEY_BYTES) to persisted.copyOfRange(AES_KEY_BYTES, BLOB_BYTES)
        }
        // Fresh install (or unreadable blob): generate once, then try to persist.
        val fresh = ByteArray(BLOB_BYTES).also { SECURE_RANDOM.nextBytes(it) }
        val saved = runCatching { keyStore.save(fresh) }.isSuccess
        return if (saved) {
            fresh.copyOfRange(0, AES_KEY_BYTES) to fresh.copyOfRange(AES_KEY_BYTES, BLOB_BYTES)
        } else {
            // Persistence unavailable -> bundled fallback; first run must not brick the install.
            bundled.aesKey to bundled.kdfSalt
        }
    }

    /**
     * The remote hot-config document as [applyRemote] parses it: `versionTag` is the
     * required gate, everything else merges when present. `aesKey`/`kdfSalt` are
     * intentionally **not** fields — with [Json.ignoreUnknownKeys] they are dropped in
     * any shape, so no remote document can deliver install keys (T5 ruling).
     */
    @Serializable
    private data class RemoteParams(
        val versionTag: Int,
        val endpointBase: String? = null,
        val signSalt: String? = null,
        val rsaPublicKey: String? = null,
    )

    private companion object {
        const val AES_KEY_BYTES = 16
        const val KDF_SALT_BYTES = 40
        const val BLOB_BYTES = AES_KEY_BYTES + KDF_SALT_BYTES

        val SECURE_RANDOM = SecureRandom()

        /** Forward-tolerant: old APKs must keep reading newer config docs (spec §5.6 schema versioning). */
        val REMOTE_JSON = Json { ignoreUnknownKeys = true }
    }
}
