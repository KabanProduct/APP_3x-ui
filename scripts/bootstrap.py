"""Download a project-local Android build toolchain from upstream sources."""
import concurrent.futures
import hashlib
import json
from pathlib import Path
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.tools'
TOOLS.mkdir(exist_ok=True)

def read(url):
    with urllib.request.urlopen(url, timeout=90) as response:
        return response.read()

def download(name, url, checksum=None):
    archive = TOOLS / (name + '.zip')
    if not archive.exists():
        temporary = archive.with_suffix('.partial')
        with urllib.request.urlopen(url, timeout=120) as response, temporary.open('wb') as out:
            while chunk := response.read(1024 * 1024):
                out.write(chunk)
        temporary.replace(archive)
    if checksum and hashlib.sha256(archive.read_bytes()).hexdigest() != checksum.strip():
        raise RuntimeError('Checksum mismatch: ' + name)
    target = TOOLS / name
    if not target.exists():
        with zipfile.ZipFile(archive) as z:
            z.extractall(target)
    print(name + ': ready', flush=True)

def jdk():
    release = json.loads(read('https://api.github.com/repos/adoptium/temurin17-binaries/releases/latest'))
    asset = next(a for a in release['assets'] if a['name'].startswith('OpenJDK17U-jdk_x64_windows_hotspot_') and a['name'].endswith('.zip'))
    checksum = read(asset['browser_download_url'] + '.sha256.txt').decode().split()[0]
    download('jdk', asset['browser_download_url'], checksum)

def gradle():
    url = 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip'
    download('gradle', url, read(url + '.sha256').decode())

def android():
    download('android-cli', 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip')

if __name__ == '__main__':
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as executor:
        for future in [executor.submit(task) for task in (jdk, gradle, android)]:
            future.result()
