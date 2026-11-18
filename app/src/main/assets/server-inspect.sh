#!/bin/sh
# Read-only inventory; an unknown/failed check can never mean a clean VPS.
complete=1
panel=ABSENT
for p in /usr/local/x-ui /etc/x-ui /opt/3x-ui /etc/systemd/system/x-ui.service; do
    if [ -e "$p" ]; then panel=PRESENT; fi
done
command -v x-ui >/dev/null 2>&1 && panel=PRESENT
printf '__3XM_PANEL=%s\n' "$panel"
printf '__3XM_UID=%s\n' "$(id -u)"
if [ -r /etc/os-release ]; then
    . /etc/os-release
    printf '__3XM_OS=%s\n' "$ID"
    printf '__3XM_OSVERSION=%s\n' "$VERSION_ID"
else complete=0; fi
printf '__3XM_ARCH=%s\n' "$(uname -m)"
if command -v systemctl >/dev/null 2>&1 && [ -d /run/systemd/system ]; then
    printf '__3XM_SYSTEMD=1\n'
    services=$(systemctl list-units --type=service --state=running --no-pager --no-legend 2>/dev/null) || complete=0
    printf '%s\n' "$services" | awk '{print $1}' | while IFS= read -r name; do
        case "$name" in
            ''|systemd-*|ssh.service|sshd.service|cron.service|crond.service|dbus.service|rsyslog.service|polkit.service|networking.service|NetworkManager.service|networkd-dispatcher.service|chrony.service|chronyd.service|ntp.service|unattended-upgrades.service|irqbalance.service|qemu-guest-agent.service|open-vm-tools.service|cloud-*|getty@*|serial-getty@*|user@*|accounts-daemon.service|multipathd.service|packagekit.service|snapd.service|udisks2.service|ModemManager.service|ufw.service|firewalld.service) ;;
            *) printf '__3XM_WORKLOAD=%s\n' "$name" ;;
        esac
    done
else printf '__3XM_SYSTEMD=0\n'; complete=0; fi
if command -v docker >/dev/null 2>&1; then
    containers=$(docker ps -a --format '{{.Image}} {{.Names}}' 2>/dev/null) || complete=0
    if [ -n "$containers" ]; then
        printf '__3XM_WORKLOAD=Docker containers\n'
        case "$containers" in *3x-ui*|*x-ui*) printf '__3XM_PANEL=PRESENT\n';; esac
    fi
fi
if command -v ss >/dev/null 2>&1; then
    listeners=$(ss -H -lntu 2>/dev/null) || complete=0
    printf '%s\n' "$listeners" | awk '{print $5}' | while IFS= read -r address; do
        port=${address##*:}
        case "$port" in ''|53|68|323|"${1:-22}") ;; *) printf '__3XM_PORT=%s\n' "$port" ;; esac
    done
else complete=0; fi
free=$(df -Pk /usr/local 2>/dev/null | awk 'NR==2 {print $4}')
case "$free" in ''|*[!0-9]*) complete=0 ;; *) printf '__3XM_DISK_KB=%s\n' "$free" ;; esac
printf '__3XM_COMPLETE=%s\n' "$complete"
