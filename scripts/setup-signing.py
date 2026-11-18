"""Create the permanent signing key once; optionally upload to this repo's Actions secrets.

Back up .signing/ securely. Never commit it or regenerate the key for an update.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import secrets
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--upload', action='store_true')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
directory = root / '.signing'
directory.mkdir(exist_ok=True)
credentials = directory / 'credentials.json'
keystore = directory / 'three-x-mobile.p12'
if not credentials.exists():
    if keystore.exists():
        raise SystemExit('Existing keystore has no credentials file. Restore credentials; do not overwrite the key.')
    credentials.write_text(json.dumps({'password': secrets.token_urlsafe(36)}), encoding='utf-8')
    credentials.chmod(0o600)
password = json.loads(credentials.read_text(encoding='utf-8'))['password']
jdk = next((root / '.tools/jdk').iterdir())
keytool = jdk / 'bin/keytool.exe'
env = os.environ.copy()
env['THREEX_SIGNING_PASSWORD'] = password
if not keystore.exists():
    subprocess.run([str(keytool), '-genkeypair', '-keystore', keystore.name, '-storetype', 'PKCS12',
        '-storepass:env', 'THREEX_SIGNING_PASSWORD', '-keypass:env', 'THREEX_SIGNING_PASSWORD',
        '-alias', 'three-x-mobile', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000',
        '-dname', 'CN=3x Mobile, O=KabanProduct', '-noprompt'], cwd=directory, env=env, check=True)
    keystore.chmod(0o600)
subprocess.run([str(keytool), '-list', '-keystore', keystore.name, '-storepass:env', 'THREEX_SIGNING_PASSWORD',
    '-alias', 'three-x-mobile'], cwd=directory, env=env, check=True)
if args.upload:
    gh = root / '.tools/gh/bin/gh.exe'
    for name, value in {
        'RELEASE_KEYSTORE_BASE64': base64.b64encode(keystore.read_bytes()).decode('ascii'),
        'RELEASE_STORE_PASSWORD': password,
        'RELEASE_KEY_PASSWORD': password,
    }.items():
        subprocess.run([str(gh), 'secret', 'set', name, '--repo', 'KabanProduct/APP_3x-ui'],
                       input=value, text=True, check=True)
        print(f'Configured GitHub Actions secret: {name}')
print('Back up the .signing directory to a secure location. Secrets were not printed.')
