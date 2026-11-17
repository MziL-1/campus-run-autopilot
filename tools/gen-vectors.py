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

Run: python tools/gen-vectors.py
Out: core/src/test/resources/vectors/netsec.json
"""

import base64
import importlib.util
import itertools
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
UPSTREAM_CRYPTO = ROOT / "reference" / "upstream" / "协议" / "7.3.92" / "core" / "crypto.py"
OUT_FILE = ROOT / "core" / "src" / "test" / "resources" / "vectors" / "netsec.json"

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


if __name__ == "__main__":
    main()
