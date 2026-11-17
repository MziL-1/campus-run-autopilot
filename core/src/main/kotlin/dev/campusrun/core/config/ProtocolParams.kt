package dev.campusrun.core.config

/**
 * Protocol parameters for the NetSecKit 4.2.4 wire layer.
 *
 * This class is the payload of the Task 10 hot-update channel ([ProtocolParams.versionTag]
 * is the version the remote config must exceed); field names and order follow the
 * phase-1a plan. Wire-format semantics come from upstream `协议/7.3.92/core/`:
 *
 * @param aesKey AES-128 key (16 bytes) for the request/response body, upstream
 *   `NetSecClient.encrypt_request` generates it per request, here it is pinned by config.
 * @param kdfSalt the four upstream `keyDataOne..Four` segments concatenated (4 x 10 bytes);
 *   fed to the Feistel-variant response KDF.
 * @param rsaPublicKey base64 bare PKCS#1 `RSAPublicKey` DER (`30 81 89 02 81 81 00 ...`),
 *   upstream `REQ_RSA_PKCS1_B64`.
 * @param endpointBase gateway base URL, upstream `core/config.py` `HOST_RUN`.
 * @param signSalt MD5 salt for `tokenSign`/`headerSign`, upstream `2slhe02lsfiwowlcixisla_sls-_slaor`.
 * @param versionTag config schema version used by the update channel.
 */
data class ProtocolParams(
    val aesKey: ByteArray,
    val kdfSalt: ByteArray,
    val rsaPublicKey: String,
    val endpointBase: String,
    val signSalt: String,
    val versionTag: Int,
)
