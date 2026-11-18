"""Release only a new explicit app version. Fail closed on GitHub/API errors."""
import json
import base64
import os
from pathlib import Path
import re
import urllib.request
import urllib.parse

source = Path('app/build.gradle.kts').read_text(encoding='utf-8')
name = re.search(r'versionName = "(\d+\.\d+\.\d+)"', source).group(1)
code = int(re.search(r'versionCode = (\d+)', source).group(1))
assert code > 0
assert Path(f'docs/releases/{name}.md').is_file(), 'Release notes are required'
headers = {'Authorization': 'Bearer ' + os.environ['GH_TOKEN'], 'User-Agent': '3x-Mobile-release', 'Accept': 'application/vnd.github+json'}
request = urllib.request.Request(f"https://api.github.com/repos/{os.environ['GITHUB_REPOSITORY']}/releases?per_page=100", headers=headers)
releases = json.load(urllib.request.urlopen(request, timeout=30))
matching = [r for r in releases if r['tag_name'] == f'v{name}']
assert not any(r['draft'] for r in matching), 'An unfinished draft exists: inspect it before retrying publication'
publish = not matching
if publish:
    previous = []
    for release in releases:
        match = re.fullmatch(r'v(\d+)\.(\d+)\.(\d+)', release['tag_name'])
        if match and not release['draft']:
            assert tuple(map(int, name.split('.'))) > tuple(map(int, match.groups())), 'Version must increase'
            previous.append((tuple(map(int, match.groups())), release['tag_name']))
    if previous:
        tag = max(previous)[1]
        url = f"https://api.github.com/repos/{os.environ['GITHUB_REPOSITORY']}/contents/app/build.gradle.kts?ref={urllib.parse.quote(tag, safe='')}"
        old = json.load(urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=30))
        old_source = base64.b64decode(old['content']).decode('utf-8')
        old_code = int(re.search(r'versionCode = (\d+)', old_source).group(1))
        assert code > old_code, 'Android versionCode must increase'
with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as out:
    out.write(f'name={name}\npublish={str(publish).lower()}\n')
print(f'Version {name}, code {code}, publish: {publish}')
