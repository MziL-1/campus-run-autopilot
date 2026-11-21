#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Generate cross-language crypto vectors from the upstream NetSecKit 4.2.4 reference.

The vectors are produced by *importing and calling* the upstream Python modules at
``reference/upstream/协议/7.3.92/core/crypto.py`` -- the crypto itself is never
reimplemented here.

One input has to be pinned for a byte-exact cross-language comparison: upstream
``rsa_pkcs1_v15_encrypt`` fills the PKCS#1 v1.5 padding string from ``os.urandom``,
which by definition cannot be reproduced by another language. The Kotlin port
(``NetSecKit.kt``, see its KDoc) derives those padding octets deterministically as
the cyclic ``response_kdf(kdfSalt)`` stream, skipping zero octets exactly like
upstream's own ``if b != 0`` loop does. This generator injects the same stream into
the *unmodified* upstream module by swapping the module's ``os`` reference, so
upstream still assembles the PKCS#1 block and the AES-CBC ciphertext itself.

The signature vectors (``vectors/sign.json``) come from the same unmodified tree:

* ``header[]``  -- ``core/crypto.py`` ``NetSecClient.encrypt_request`` wrapped by
  ``core/client.py`` ``build_headers`` (``headerSign`` is the compact JSON of the
  ``{d,h,k,p,t}`` wire dict). The generator pins the three random sources of
  ``encrypt_request`` -- the request AES key (``params.aesKey`` is fixed by config),
  the ``keyDataOne..Four`` segments (derived from a UUID, pinned from the vector's
  ``kdfSalt``) and ``time.time()`` (pinned to the vector's ``timeStamp`` header) --
  and the PKCS#1 padding stream (same swap as above), so upstream still assembles
  every byte itself. Upstream appends ``ABCDEFGHIJKLMN12`` to the 32-char UUID, so a
  vector ``kdfSalt`` necessarily ends with ``ABCDEFGH``.
* ``token[]``   -- ``core/crypto.py`` ``compute_token_sign`` over the single ``token``
  entry the Kotlin interface exposes.
* ``tokenFull[]`` -- ``compute_token_sign`` over upstream ``build_headers``' real
  ``sign_dict`` (``{timeStamp, token, uid}`` + per-request ``sign_params``), i.e. the
  macro ``ApiClient`` (Task 5) has to reproduce.
* ``wire[]``    -- ``encrypt_request`` over a compact POST body JSON (same envelope as
  ``headerSign``), pinned so the Kotlin request-wire helper is cross-checked for bodies.
* ``settlement[]`` -- ``running/builder.py`` ``compute_sport_signature`` over 31-field
  ``sign_map``s; the emitted ``fields`` are the ``k=v`` strings of upstream's own
  ``compute_original_sign`` (so value formatting/bool lowercasing is upstream's).
  ``running/`` is loaded as a synthetic package so ``running/__init__.py`` (which
  pulls in the Python>=3.12-only ``client.py`` f-string) is never imported.
  The last case (``track-derived-760s``) is built from a *fixed synthetic track*
  through the same field derivations upstream ``build_submission_payload`` applies
  (``avgStepFreq``/``complete`` formulas, ``stop-start == duration*1000`` from
  ``consistency.validate_totals``), plus the ``fivePointJson`` envelope and the
  first/last 10 s buckets straight out of upstream ``time_buckets`` -- it is the
  golden Task 8's ``Settlement.buildRequest`` test reconstructs end to end.
  **Exception: the ``speed`` field is NOT upstream's.** Upstream ``builder.py:162``
  computes average *speed* (m/s x1000), but the platform consumes ``speed`` as
  平均配速 (min/km x1000) and rejects anything outside ``speedTop 3.0`` ..
  ``speedBottom 10.0`` 分/km, so upstream's value (2671 for the canonical 2030 m /
  760 s run) fails the 配速 rule for every submission. Every settlement ``speed`` is
  therefore overridden with the Kotlin port's pace formula *after* the upstream
  derivations -- see ``.superpowers/sdd/pace-diagnosis.md`` (root cause §3) and the
  comment on ``pace_milli`` below.

Run: python tools/gen-vectors.py
Out: core/src/test/resources/vectors/netsec.json
     core/src/test/resources/vectors/sign.json
     core/src/test/resources/session/gmttime.json
"""

import base64
import importlib.util
import itertools
import json
import sys
import types
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
UPSTREAM = ROOT / "reference" / "upstream" / "协议" / "7.3.92"
UPSTREAM_CRYPTO = UPSTREAM / "core" / "crypto.py"
UPSTREAM_RUNNING = UPSTREAM / "running"
OUT_FILE = ROOT / "core" / "src" / "test" / "resources" / "vectors" / "netsec.json"
SIGN_FILE = ROOT / "core" / "src" / "test" / "resources" / "vectors" / "sign.json"
SESSION_FILE = ROOT / "core" / "src" / "test" / "resources" / "session" / "gmttime.json"

RSA_MODULUS_BYTES = 128  # upstream REQ RSA key is 1024-bit (core/crypto.py NetSecClient)


def load_upstream(path):
    if not path.is_file():
        sys.exit("missing upstream module: %s" % path)
    spec = importlib.util.spec_from_file_location("upstream_netsec_crypto", str(path))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class _DeterministicOs(object):
    """Minimal stand-in for the ``os`` module as seen by rsa_pkcs1_v15_encrypt."""

    def __init__(self, stream):
        self._stream = stream

    def urandom(self, count):
        return bytes(next(self._stream) for _ in range(count))


def kdf_seed(crypto, kdf_salt):
    if len(kdf_salt) != 40:
        raise ValueError("kdfSalt must be the four upstream keyData segments (40 bytes)")
    segments = [kdf_salt[i * 10:(i + 1) * 10] for i in range(4)]
    seed = crypto.response_kdf(*segments)
    if not any(seed):
        raise ValueError("response_kdf returned an all-zero seed")
    return seed


def wrap_aes_key(crypto, client, aes_key, kdf_salt):
    """Upstream RSA PKCS#1 v1.5 wrap of aes_key with PRF-derived padding octets."""
    stream = itertools.cycle(kdf_seed(crypto, kdf_salt))
    real_os = crypto.os
    crypto.os = _DeterministicOs(stream)
    try:
        wrapped = crypto.rsa_pkcs1_v15_encrypt(aes_key, client.n, client.e, RSA_MODULUS_BYTES)
    finally:
        crypto.os = real_os
    if len(wrapped) != RSA_MODULUS_BYTES:
        raise AssertionError("unexpected wrapped key length")
    return wrapped


def build_case(crypto, client, name, plain, aes_key, kdf_salt):
    wrapped = wrap_aes_key(crypto, client, aes_key, kdf_salt)
    # Guard against silently falling back to os.urandom: the wrap must be reproducible.
    if wrapped != wrap_aes_key(crypto, client, aes_key, kdf_salt):
        raise AssertionError("deterministic padding stream was not applied")
    cipher = crypto.aes128_cbc_encrypt(plain, aes_key)
    if crypto.aes128_cbc_decrypt(cipher, aes_key) != plain:
        raise AssertionError("upstream AES round-trip failed for case %s" % name)
    response_key = kdf_seed(crypto, kdf_salt)
    response_plain = json.dumps(
        {"data": base64.b64encode(plain).decode("ascii")}, separators=(",", ":")
    ).encode("utf-8")
    response_cipher = crypto.aes128_cbc_encrypt(response_plain, response_key)
    if crypto.aes128_cbc_decrypt(response_cipher, response_key) != response_plain:
        raise AssertionError("upstream response round-trip failed for case %s" % name)
    return {
        "name": name,
        "plainHex": plain.hex(),
        "aesKeyHex": aes_key.hex(),
        "kdfSaltHex": kdf_salt.hex(),
        "rsaPublicKey": base64.b64encode(
            crypto.base64.b64decode(crypto.REQ_RSA_PKCS1_B64)
        ).decode("ascii"),
        "wrappedKeyHex": wrapped.hex(),
        "cipherHex": (wrapped + cipher).hex(),
        "responsePlainHex": response_plain.hex(),
        "responseCipherHex": response_cipher.hex(),
    }


def netsec_cases(crypto):
    client = crypto.NetSecClient()
    inner = {
        "data": base64.b64encode(b'{"runMode":1}').decode("ascii"),
        "keyDataFour": "keydata001",
        "keyDataOne": "abcdefghij",
        "keyDataThree": "KLMNOPQRST",
        "keyDataTwo": "uvwxyz0123",
        "platform": 2,
        "timeStamp": 1750000000,
    }
    inner_json = json.dumps(inner, separators=(",", ":"), ensure_ascii=False) + chr(10)

    # The last case's response-KDF seed contains a 0x00 octet, so the padding-octet
    # skip loop (upstream `if b != 0`) is exercised by a pinned vector.
    specs = [
        ("empty-plaintext", b"", b"0123456789abcdef",
         b"deadbeef01cafebabe02feedface030123456789"),
        ("json-body", b'{"runMode":1,"semesterId":20241}', b"zyxwvutsrqponmlk",
         b"0123456789abcdefghijKLMNOPQRSTuvwxyz0123"),
        ("aligned-16-bytes", b"0123456789abcdef", b"0011223344556677",
         b"runMode001semester02timeStamp3platform04"),
        ("inner-json-newline", inner_json.encode("utf-8"), b"qwertyuiopasdfgh",
         b"abcdefghijuvwxyz0123KLMNOPQRSTkeydata001"),
        ("kdf-seed-zero-octet", b'{"pageNum":1}', b"9f8e7d6c5b4a3928",
         b"waK4bdraiSsw5ReQDiXTa7BMQ68YGOPbiqL2GFh1"),
    ]
    cases = []
    for spec in specs:
        cases.append(build_case(crypto, client, *spec))
    if b"\x00" not in kdf_seed(crypto, specs[-1][3]):
        raise AssertionError("kdf-seed-zero-octet case no longer contains a zero octet")
    return cases


def load_upstream_running(running_dir):
    """Load running/{models,consistency,config,builder} without running/__init__.py.

    running/__init__.py imports running/client.py, whose f-string uses PEP 701 syntax
    (Python >= 3.12). A synthetic package keeps the suite unpatched.
    """
    if not running_dir.is_dir():
        sys.exit("missing upstream running package: %s" % running_dir)
    package = types.ModuleType("upstream_running")
    package.__path__ = [str(running_dir)]
    sys.modules["upstream_running"] = package
    for name in ("models", "consistency", "config", "builder"):
        path = running_dir / (name + ".py")
        if not path.is_file():
            sys.exit("missing upstream running module: %s" % path)
        spec = importlib.util.spec_from_file_location("upstream_running." + name, str(path))
        module = importlib.util.module_from_spec(spec)
        sys.modules["upstream_running." + name] = module
        spec.loader.exec_module(module)
    return sys.modules["upstream_running.builder"]


class _FixedUuid(object):
    """``str(uuid.uuid4())`` stand-in; upstream strips dashes from it."""

    def __init__(self, text):
        self._text = text

    def __str__(self):
        return self._text


class _FixedUuidModule(object):
    def __init__(self, text):
        self._text = text

    def uuid4(self):
        return _FixedUuid(self._text)


class _FixedRandom(object):
    """``random.choice`` stand-in driving upstream's per-request AES key."""

    def __init__(self, chars):
        self._chars = list(chars)
        self._index = 0

    def choice(self, seq=None):
        char = self._chars[self._index]
        self._index += 1
        return char


class _FixedTime(object):
    def __init__(self, seconds):
        self._seconds = seconds

    def time(self):
        return self._seconds


def inner_timestamp_ms(seconds):
    return int(seconds) * 1000


def pinned_wire(crypto, name, plaintext, aes_key, kdf_salt, seconds):
    """Run upstream ``encrypt_request`` twice with every random/clock source pinned."""
    if len(aes_key) != 16:
        raise ValueError("aesKey must be 16 bytes")
    if len(kdf_salt) != 40:
        raise ValueError("kdfSalt must be the four 10-byte keyData segments (40 bytes)")
    # upstream: candidate = str(uuid.uuid4()).replace("-", "") + "ABCDEFGHIJKLMN12"
    if kdf_salt[32:] != b"ABCDEFGH":
        raise ValueError("kdfSalt must end with the upstream fixed UUID suffix ABCDEFGH")

    client = crypto.NetSecClient()
    results = []
    for _ in range(2):
        stream = itertools.cycle(kdf_seed(crypto, kdf_salt))
        real_uuid, real_random, real_time, real_os = (
            crypto.uuid, crypto.random, crypto.time, crypto.os)
        crypto.uuid = _FixedUuidModule(kdf_salt[:32].decode("ascii"))
        crypto.random = _FixedRandom(aes_key.decode("ascii"))
        crypto.time = _FixedTime(seconds + 0.789)
        crypto.os = _DeterministicOs(stream)
        try:
            wire, _ = client.encrypt_request(plaintext)
        finally:
            crypto.uuid, crypto.random, crypto.time, crypto.os = (
                real_uuid, real_random, real_time, real_os)
        results.append(json.dumps(wire, separators=(",", ":")))
    # Guard: no unpatched random/clock source may leak into the pinned wire.
    if results[0] != results[1]:
        raise AssertionError("NetSecKit wire is not reproducible for case %s" % name)
    return results[0]


def header_case(crypto, name, headers, aes_key, kdf_salt, seconds):
    """One upstream ``encrypt_request`` round producing the exact headerSign value."""
    if str(headers.get("timeStamp")) != str(inner_timestamp_ms(seconds)):
        raise ValueError("headers.timeStamp must be the millisecond form of %d" % seconds)

    headers_json = json.dumps(headers, separators=(",", ":"), ensure_ascii=False)
    header_sign = pinned_wire(crypto, name, headers_json, aes_key, kdf_salt, seconds)
    return {
        "name": name,
        "headerOrder": list(headers.keys()),
        "headers": headers,
        "headersJson": headers_json,
        "aesKeyHex": aes_key.hex(),
        "kdfSaltHex": kdf_salt.hex(),
        "rsaPublicKey": base64.b64encode(
            crypto.base64.b64decode(crypto.REQ_RSA_PKCS1_B64)
        ).decode("ascii"),
        "headerSign": header_sign,
    }


def wire_cases(crypto):
    """POST body envelope: ``encrypt_request(compact_body_json)`` -> ``{d,h,k,p,t}``."""
    aes_key = b"postbodykey00001"
    kdf_salt = b"abcdefghijklmnopqrstuvwxyz012345ABCDEFGH"
    seconds = 1750123456
    bodies = [
        ("post-body-object", {"runMode": 1, "semesterId": 20241}),
        ("post-body-empty", {}),
        ("post-body-utf8", {"address": "北京市海淀区", "runId": "42"}),
    ]
    return [
        {
            "name": name,
            "plainText": json.dumps(body, separators=(",", ":"), ensure_ascii=False),
            "timeStampMs": inner_timestamp_ms(seconds),
            "aesKeyHex": aes_key.hex(),
            "kdfSaltHex": kdf_salt.hex(),
            "rsaPublicKey": base64.b64encode(
                crypto.base64.b64decode(crypto.REQ_RSA_PKCS1_B64)
            ).decode("ascii"),
            "wire": pinned_wire(
                crypto, name, json.dumps(body, separators=(",", ":"), ensure_ascii=False),
                aes_key, kdf_salt, seconds),
        }
        for name, body in bodies
    ]


def header_cases(crypto):
    specs = [
        (
            "identity-headers",
            {
                "Accept": "application/json",
                "Content-Type": "application/json",
                "appVersion": "7.3.92",
                "deviceName": "Android Device",
                "DeviceId": "00000000000000000000000000000000",
                "osVersion": "13",
                "timeStamp": str(inner_timestamp_ms(1750123456)),
                "nonce": "00000000-0000-4000-8000-000000000001",
                "uid": "90001",
                "token": "tok-header-case-1",
            },
            b"k1l2m3n4o5p6q7r8",
            b"abcdefghijklmnopqrstuvwxyz012345ABCDEFGH",
            1750123456,
        ),
        (
            "utf8-header-values",
            {
                "appVersion": "7.3.92",
                "deviceName": "安卓设备（测试）",
                "campusName": "示例大学",
                "timeStamp": str(inner_timestamp_ms(1760000001)),
                "nonce": "00000000-0000-4000-8000-000000000002",
                "uid": "90002",
                "token": "tok-header-case-2",
            },
            b"a1b2c3d4e5f6g7h8",
            b"ABCDEFGHIJKLMNOPQRSTUVWXYZ012345ABCDEFGH",
            1760000001,
        ),
        (
            "quoted-and-empty-headers",
            {
                "appVersion": "7.3.92",
                "deviceName": "A\"B\\C",
                "timeStamp": str(inner_timestamp_ms(1700000000)),
                "nonce": "00000000-0000-4000-8000-000000000003",
                "uid": "",
                "token": "",
            },
            b"z9y8x7w6v5u4t3s2",
            b"0123456789abcdefghijklmnopqrstuvABCDEFGH",
            1700000000,
        ),
    ]
    cases = []
    for name, headers, aes_key, kdf_salt, seconds in specs:
        # Same session sign_dict shape as upstream build_headers, so headerSign
        # carries a real tokenSign value (the Kotlin tokenSign interface is narrower).
        headers["tokenSign"] = crypto.compute_token_sign({
            "timeStamp": headers["timeStamp"],
            "token": headers["token"],
            "uid": headers["uid"],
        })
        cases.append(header_case(crypto, name, headers, aes_key, kdf_salt, seconds))
    return cases


def token_cases(crypto):
    salt = "2slhe02lsfiwowlcixisla_sls-_slaor"
    specs = [
        ("ascii-token", "tok-0123456789", salt),
        ("empty-token", "", salt),
        ("separator-chars-in-token", "tok&en=1?x", salt),
        ("utf8-token-custom-salt", "令牌-测试", "custom-salt-测试"),
    ]
    return [
        {
            "name": name,
            "token": token,
            "salt": case_salt,
            "sign": crypto.compute_token_sign({"token": token}, case_salt),
        }
        for name, token, case_salt in specs
    ]


def token_full_cases(crypto):
    """``build_headers`` sign_dict: ``{timeStamp, token, uid}`` (+ ``sign_params``)."""
    default_salt = "2slhe02lsfiwowlcixisla_sls-_slaor"
    specs = [
        (
            "session-headers",
            {"timeStamp": "1750123456789", "token": "tok-0123456789", "uid": "90001"},
            default_salt,
        ),
        (
            "anonymous-empty-credentials",
            {"timeStamp": "1750123456789", "token": "", "uid": ""},
            default_salt,
        ),
        (
            "utf8-credentials-custom-salt",
            {"timeStamp": "1760000001000", "token": "令牌-测试", "uid": "90002"},
            "custom-salt-测试",
        ),
        (
            "extra-sign-params",
            {"timeStamp": "1750123456789", "token": "tok", "uid": "90003",
             "mode": "1", "runId": "42"},
            default_salt,
        ),
    ]
    return [
        {
            "name": name,
            "salt": case_salt,
            "signParams": sign_params,
            "sign": crypto.compute_token_sign(sign_params, case_salt),
        }
        for name, sign_params, case_salt in specs
    ]


def session_cases(crypto):
    """``profile/client.py`` ``get_server_time``: one GMT envelope per skew direction.

    The server returns the NetSec response envelope ``{"r":..,"s":..,"v":101}``; ``r`` is
    AES-128-CBC under ``response_kdf(keyDataOne..Four)`` of ``{"data": base64(business)}``,
    and the business object is ``{"error":10000,"data":<epoch ms>}``. ``s``/``v`` are
    opaque and ignored by the client (upstream reads only ``r``).
    """
    aes_key = b"0123456789abcdef"
    kdf_salt = b"0123456789abcdefghijABCDEFGHIJKLMNOPQRST"
    response_key = kdf_seed(crypto, kdf_salt)
    local_ms = 1750123454000
    specs = [
        ("server-ahead-2789ms", local_ms, 2789),
        ("server-behind-3150ms", local_ms, -3150),
    ]
    cases = []
    for name, case_local_ms, delta_ms in specs:
        server_ms = case_local_ms + delta_ms
        business = json.dumps(
            {"error": 10000, "data": server_ms}, separators=(",", ":")
        ).encode("utf-8")
        inner = json.dumps(
            {"data": base64.b64encode(business).decode("ascii")},
            separators=(",", ":"),
        ).encode("utf-8")
        cipher = crypto.aes128_cbc_encrypt(inner, response_key)
        if crypto.aes128_cbc_decrypt(cipher, response_key) != inner:
            raise AssertionError("upstream response round-trip failed for %s" % name)
        envelope = json.dumps(
            {"r": base64.b64encode(cipher).decode("ascii"), "s": "0", "v": 101},
            separators=(",", ":"),
        )
        cases.append({
            "name": name,
            "path": "/api/v3/getserver/gmttime",
            "localMs": case_local_ms,
            "serverMs": server_ms,
            "aesKeyHex": aes_key.hex(),
            "kdfSaltHex": kdf_salt.hex(),
            "rsaPublicKey": base64.b64encode(
                crypto.base64.b64decode(crypto.REQ_RSA_PKCS1_B64)
            ).decode("ascii"),
            "responseBody": envelope,
        })
    return cases



def settlement_case(builder, name, sign_map, salt=None):
    if salt is None:
        salt = builder.SPORT_STATIC_SALT
    signature = builder.compute_sport_signature(sign_map, salt)
    original = builder.compute_original_sign(sign_map)
    fields = original.split("&")
    if any("&" in str(value) for value in sign_map.values()):
        raise AssertionError("settlement vector values must not contain '&'")
    return {
        "name": name,
        "salt": salt,
        "fieldCount": len(fields),
        "fields": fields,
        "signature": signature,
    }


def pace_milli(total_dis, duration_sec):
    """Settlement ``speed`` as the platform reads it: average PACE in min/km x1000.

    Deliberate deviation from upstream ``running/builder.py:162``
    (``round(totalDis / duration * 1000)``, average speed in m/s x1000): the platform
    renders ``speed / 1000`` as 平均配速 in 分/km and checks it against
    ``speedTop 3.0`` .. ``speedBottom 10.0`` 分/km, so upstream's formula (2671 for
    the canonical 2030 m / 760 s run -> 2.671 分/km < 3.0) fails the 配速 rule on
    every record while the true pace 6.240 分/km passes. Evidence and the full
    key-by-key analysis: ``.superpowers/sdd/pace-diagnosis.md`` (root cause §3).
    The Kotlin port (``Settlement.averagePaceMilli``) uses the identical formula;
    Python's ``round`` is half-even there too (``pyRound``).
    """
    return round(duration_sec * 1_000_000 / (60 * total_dis))


def settlement_build_case(builder, consistency, models):
    """Track-derived settlement golden for Task 8's Settlement.buildRequest test.

    The fixed inputs below mirror the Kotlin ``SettlementTest`` fixture exactly; every
    derived value follows upstream ``running/builder.py`` ``build_submission_payload``
    (the ``speed`` bullet is the one documented exception):

    - ``totalDis``  = round(haversine path length of the fixed track) -- the Kotlin port
      derives the same number from ``Geo.pathLengthM`` (same radius, same formula).
    - ``totalSteps``= round(sum(cadence * dt) / 60.0): ``TrackPoint.stepRateHz`` carries
      steps-per-minute, so cadence*dt/60 is the step count of each 1 s interval
      (upstream's own ``avgStepFreq`` then confirms the unit: steps/min).
    - ``avgStepFreq`` = round(totalSteps * 60.0 / totalTime)   (builder.py:161)
    - ``speed``       = round(totalTime * 1e6 / (60 * totalDis)) -- PACE in min/km x1000.
      DELIBERATE deviation from builder.py:162 (average speed m/s x1000): the platform
      reads ``speed`` as 配速 and rejects it outside speedTop 3.0 .. speedBottom 10.0
      分/km, so upstream's value would fail every submission. See
      ``.superpowers/sdd/pace-diagnosis.md`` (root cause §3) and ``pace_milli``.
    - ``complete``/``unCompleteReason``                        (builder.py:159-160)
    - ``stop - start == duration * 1000`` is required by consistency.validate_totals:48,
      so the golden handle timestamps are pinned to the duration.
    """
    duration = 760
    lat, lng0, lng_step, speed_mps, cadence = 34.705, 113.7, 0.00003, 2.73, 156.0
    uid, unid = 90001, 55001
    uuid_str = "0b9c2f8e-4b6a-4f1e-9d2c-7a5e3f1b8c4d"
    start_ms, stop_ms = 1750123456000, 1750124216000
    sel_distance, sel_run_time, policy = 2000, 0, 1
    calorie, avg_power = 135, 150
    required_points = [(34.7018, 113.7021), (34.7042, 113.7055)]

    if stop_ms - start_ms != duration * 1000:
        raise AssertionError("golden handle timestamps must span the track duration")
    # Track fixture: 1 Hz samples, straight east line (identical arithmetic in Kotlin).
    track = [(lat, lng0 + i * lng_step, i, speed_mps, cadence) for i in range(duration + 1)]
    path_m = sum(
        consistency.distance(track[i][:2], track[i + 1][:2]) for i in range(duration)
    )
    total_dis = round(path_m)
    total_steps = round(sum(p[4] * 1 for p in track[:duration]) / 60.0)
    avg_step_freq = round(total_steps * 60.0 / duration)
    # `speed` is the pace override (see pace_milli / module docstring), applied to the
    # upstream-derived sign_map before compute_sport_signature, so signature and
    # originalSign in the golden cover the pace value exactly as Settlement.kt emits it.
    speed_val = pace_milli(total_dis, duration)
    complete = (total_dis >= sel_distance) and (duration >= sel_run_time)
    un_complete_reason = 0 if complete else 10

    sign_map = {
        "address": "",
        "avgPower": avg_power,
        "avgStepFreq": avg_step_freq,
        "calorie": calorie,
        "complete": complete,
        "errorCode": 0,
        "faceCheck": 0,
        "geeToken": "",
        "getPrize": False,
        "goalId": "null",
        "policy": policy,
        "selDistance": sel_distance,
        "selRunTime": sel_run_time,
        "selectedUnid": unid,
        "speed": speed_val,
        "sportType": 1,
        "startTime": start_ms,
        "status": 1,
        "stopTime": stop_ms,
        "themeId": 0,
        "totalAscent": 0,
        "totalDis": total_dis,
        "totalSteps": total_steps,
        "totalTime": duration,
        "uid": uid,
        "unCompleteReason": un_complete_reason,
        "unauthorized": 0,
        "useMobilityTools": 0,
        "uuid": uuid_str,
        "validDis": total_dis,
        "validTime": duration,
    }
    if len(sign_map) != 31:
        raise AssertionError("track-derived settlement sign_map must have 31 fields")
    signature = builder.compute_sport_signature(sign_map)
    original = builder.compute_original_sign(sign_map)

    # fivePointJson: upstream builder.py:210-219 envelope for sport_type=1, run_area_id=-1.
    inner = [
        {"lat": p[0], "lon": p[1], "isFixed": 1, "isPass": True,
         "pointName": "P_%d" % (i + 1), "position": i + 1}
        for i, p in enumerate(required_points)
    ]
    five_point_json = json.dumps({
        "useZip": False,
        "fivePointJson": json.dumps(inner, separators=(",", ":")),
        "runAreaId": -1,
        "freedomShowFence": False,
    }, separators=(",", ":"))

    # First/last 10 s buckets straight from upstream time_buckets + models to_dict.
    buckets = list(consistency.time_buckets(duration, total_steps, start_ms, stop_ms, total_dis))

    def speed_bucket(index):
        begin, end, _count, length = buckets[index]
        return models.SpeedBucket(length, begin, end, queueNum=index + 1).to_dict()

    def step_bucket(index):
        begin, end, count, _length = buckets[index]
        return models.StepBucket(count, begin, end, queueNum=index + 1).to_dict()

    return {
        "name": "track-derived-760s",
        "salt": builder.SPORT_STATIC_SALT,
        "fieldCount": 31,
        "fields": original.split("&"),
        "signature": signature,
        "originalSign": original,
        "inputs": {
            "track": {"durationSec": duration, "lat": lat, "lng0": lng0,
                      "lngStep": lng_step, "speedMps": speed_mps, "cadence": cadence},
            "handle": {"uid": uid, "unid": unid, "uuid": uuid_str,
                       "startTimeMs": start_ms, "stopTimeMs": stop_ms},
            "requiredPoints": [list(p) for p in required_points],
            "derived": {"totalDis": total_dis, "totalSteps": total_steps,
                        "avgStepFreq": avg_step_freq, "speed": speed_val,
                        "complete": complete, "unCompleteReason": un_complete_reason},
        },
        "fivePointJson": five_point_json,
        "bucketCount": len(buckets),
        "firstSpeedBucket": speed_bucket(0),
        "lastSpeedBucket": speed_bucket(len(buckets) - 1),
        "firstStepBucket": step_bucket(0),
        "lastStepBucket": step_bucket(len(buckets) - 1),
    }


def settlement_cases(builder, consistency, models):
    base = {
        "address": "",
        "avgPower": 150,
        "avgStepFreq": 158,
        "calorie": 135,
        "complete": True,
        "errorCode": 0,
        "faceCheck": 0,
        "geeToken": "",
        "getPrize": False,
        "goalId": "null",
        "policy": 1,
        "selDistance": 2000,
        "selRunTime": 0,
        "selectedUnid": 55001,
        # PACE (min/km x1000) via pace_milli, not upstream builder.py:162 average speed:
        # the platform reads `speed` as 配速 and validates speedTop 3.0..speedBottom 10.0
        # 分/km (.superpowers/sdd/pace-diagnosis.md). 2000 m / 760 s -> 6333 (6.333 分/km).
        # These two cases are signer-only vectors, updated for semantic consistency so no
        # golden in this repo still carries the rejected speed semantics.
        "speed": pace_milli(2000, 760),
        "sportType": 1,
        "startTime": 1750123456000,
        "status": 1,
        "stopTime": 1750124216000,
        "themeId": 0,
        "totalAscent": 0,
        "totalDis": 2000,
        "totalSteps": 1976,
        "totalTime": 760,
        "uid": 90001,
        "unCompleteReason": 0,
        "unauthorized": 0,
        "useMobilityTools": 0,
        "uuid": "0b9c2f8e-4b6a-4f1e-9d2c-7a5e3f1b8c4d",
        "validDis": 2000,
        "validTime": 760,
    }
    if len(base) != 31:
        raise AssertionError("upstream settlement sign_map must have 31 fields")
    incomplete = dict(base)
    incomplete.update({
        "complete": False,
        "selDistance": 3000,
        "totalDis": 2150,
        "validDis": 1900,
        # Pace recomputed for this case's own distance (2150 m / 760 s -> 5891), so the
        # inherited base speed cannot contradict totalDis.
        "speed": pace_milli(2150, 760),
        "unCompleteReason": 10,
        "goalId": "goal-42",
        "geeToken": "gee-token-value",
    })
    mixed = {
        "Address": "北京市海淀区",
        "avgPower": 150,
        "B": 2,
        "a": 1,
        "zeta": True,
        "empty": "",
    }
    return [
        settlement_case(builder, "31-fields-complete", base),
        settlement_case(builder, "31-fields-incomplete", incomplete),
        settlement_case(builder, "mixed-case-keys-custom-salt", mixed, "custom-salt-测试"),
        settlement_build_case(builder, consistency, models),
    ]


def main():
    crypto = load_upstream(UPSTREAM_CRYPTO)
    payload = {
        "source": "reference/upstream/协议/7.3.92/core/crypto.py",
        "generatedBy": "tools/gen-vectors.py",
        "envelope": "rsa_pkcs1_v15_encrypt(aesKey) || aes128_cbc_encrypt(plain, aesKey, iv=0)",
        "response": "aes128_cbc_encrypt(inner, response_kdf(keyDataOne..Four), iv=0)",
        "cases": netsec_cases(crypto),
    }
    OUT_FILE.parent.mkdir(parents=True, exist_ok=True)
    OUT_FILE.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("wrote %s (%d cases)" % (OUT_FILE.relative_to(ROOT), len(payload["cases"])))

    builder = load_upstream_running(UPSTREAM_RUNNING)
    consistency = sys.modules["upstream_running.consistency"]
    models = sys.modules["upstream_running.models"]
    sign_payload = {
        "source": "reference/upstream/协议/7.3.92/{core/crypto.py,core/client.py,running/builder.py}",
        "generatedBy": "tools/gen-vectors.py",
        "headerSign": "json({d,h,k,p,t}) from NetSecClient.encrypt_request(headersJson), "
                      "h = md5(inner + '\\n'), keyData*/timeStamp pinned",
        "tokenSign": "compute_token_sign({'token': token}, salt)",
        "settlement": "compute_sport_signature(sign_map, salt); fields = k=v of "
                      "compute_original_sign",
        "header": header_cases(crypto),
        "token": token_cases(crypto),
        "tokenFull": token_full_cases(crypto),
        "wire": wire_cases(crypto),
        "settlement": settlement_cases(builder, consistency, models),
    }
    SIGN_FILE.parent.mkdir(parents=True, exist_ok=True)
    SIGN_FILE.write_text(json.dumps(sign_payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("wrote %s (header=%d, token=%d, tokenFull=%d, wire=%d, settlement=%d)" % (
        SIGN_FILE.relative_to(ROOT),
        len(sign_payload["header"]),
        len(sign_payload["token"]),
        len(sign_payload["tokenFull"]),
        len(sign_payload["wire"]),
        len(sign_payload["settlement"]),
    ))

    session_payload = {
        "source": "reference/upstream/协议/7.3.92/{core/crypto.py,profile/client.py}",
        "generatedBy": "tools/gen-vectors.py",
        "response": "server body {\"r\",\"s\",\"v\"}; r = aes128_cbc_encrypt("
                    "{\"data\": base64(business)}, response_kdf(keyDataOne..Four)); "
                    "business = {\"error\":10000,\"data\":<server epoch ms>}",
        "cases": session_cases(crypto),
    }
    SESSION_FILE.parent.mkdir(parents=True, exist_ok=True)
    SESSION_FILE.write_text(
        json.dumps(session_payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("wrote %s (%d cases)" % (
        SESSION_FILE.relative_to(ROOT), len(session_payload["cases"])))


if __name__ == "__main__":
    main()
