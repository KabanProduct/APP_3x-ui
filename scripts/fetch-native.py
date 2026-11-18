"""Fetch the pinned upstream AAR, verifying its GitHub release digest."""
import hashlib
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / 'app/libs/libv2ray.aar'
URL = 'https://github.com/2dust/AndroidLibXrayLite/releases/download/v26.11.9/libv2ray.aar'
SHA256 = 'cdfef146218233a31a8b52c8b1016557730d3b63fc121d4c84cacea50e669155'
TARGET.parent.mkdir(exist_ok=True)
if not TARGET.exists():
    with urllib.request.urlopen(URL, timeout=120) as response:
        data = response.read()
    assert hashlib.sha256(data).hexdigest() == SHA256, 'Native archive checksum mismatch'
    TARGET.write_bytes(data)
assert hashlib.sha256(TARGET.read_bytes()).hexdigest() == SHA256, 'Native archive checksum mismatch'
print('AndroidLibXrayLite v26.11.9: SHA-256 verified')
