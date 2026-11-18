#!/bin/bash
# Minimal pinned binary installation. No execution of a mutable remote shell script.
set -euo pipefail
umask 077
test "$(id -u)" = 0
test ! -e /usr/local/x-ui
test ! -e /etc/x-ui
test ! -e /etc/systemd/system/x-ui.service
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq >/dev/null
apt-get install -y -qq curl ca-certificates tar openssl >/dev/null
work=$(mktemp -d /tmp/three-x-install.XXXXXXXX)
# Only the freshly-created private download directory is removed.
trap 'rm -rf -- "$work"' EXIT
curl --fail --location --proto '=https' --tlsv1.2 --max-time 300 \
    'https://github.com/MHSanaei/3x-ui/releases/download/v3.7.0/x-ui-linux-@@ARCH@@.tar.gz' -o "$work/panel.tar.gz" 2>/dev/null
echo '@@SHA256@@  '"$work/panel.tar.gz" | sha256sum -c - >/dev/null
tar -xzf "$work/panel.tar.gz" -C "$work"
test -x "$work/x-ui/x-ui"
install -d -m 700 /etc/x-ui
cp -a "$work/x-ui" /usr/local/x-ui
chmod 700 /usr/local/x-ui
cd /usr/local/x-ui
./x-ui setting -username '@@USER@@' -password '@@PASSWORD@@' -port '@@PORT@@' -webBasePath '/@@BASE@@/' -listenIP 127.0.0.1 >/dev/null
openssl req -x509 -newkey rsa:3072 -nodes -sha256 -days 365 \
    -keyout /etc/x-ui/mobile-panel.key -out /etc/x-ui/mobile-panel.crt \
    -subj '/CN=@@HOST@@' -addext 'subjectAltName=@@SAN@@' >/dev/null 2>&1
./x-ui setting -webCert /etc/x-ui/mobile-panel.crt -webCertKey /etc/x-ui/mobile-panel.key >/dev/null
./x-ui setting -listenIP 0.0.0.0 >/dev/null
cat > /etc/systemd/system/x-ui.service <<'UNIT'
[Unit]
Description=3x-ui panel
After=network-online.target
Wants=network-online.target
[Service]
Type=simple
WorkingDirectory=/usr/local/x-ui
ExecStart=/usr/local/x-ui/x-ui
Restart=on-failure
RestartSec=5
LimitNOFILE=1048576
UMask=0077
[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable --now x-ui >/dev/null
for attempt in $(seq 1 30); do
    if curl --fail --silent --cacert /etc/x-ui/mobile-panel.crt --resolve '@@HOST@@:@@PORT@@:127.0.0.1' 'https://@@HOST@@:@@PORT@@/@@BASE@@/csrf-token' >/dev/null; then
        printf '__3XM_CERT='
        openssl x509 -in /etc/x-ui/mobile-panel.crt -outform DER | sha256sum | cut -d' ' -f1
        exit 0
    fi
    sleep 1
done
exit 1
