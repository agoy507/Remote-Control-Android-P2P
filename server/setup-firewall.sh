#!/bin/bash
# Multidevice Remote — Script Otomatisasi Firewall PC B (Oracle Linux / RHEL / Ubuntu)
set -e

echo "=== Mengonfigurasi Firewall untuk Multidevice Remote ==="

# Cek apakah firewalld aktif
if systemctl is-active --quiet firewalld; then
    echo "[+] Mendeteksi firewalld aktif. Membuka port..."
    sudo firewall-cmd --permanent --zone=public --add-port=3478/tcp
    sudo firewall-cmd --permanent --zone=public --add-port=3478/udp
    sudo firewall-cmd --permanent --zone=public --add-port=49160-49200/udp
    sudo firewall-cmd --reload
    echo "[✓] Port berhasil dibuka di firewalld:"
    sudo firewall-cmd --zone=public --list-ports
elif command -v ufw >/dev/null 2>&1 && ufw status | grep -q "Status: active"; then
    echo "[+] Mendeteksi UFW aktif. Membuka port..."
    sudo ufw allow 3478/tcp comment 'Multidevice Remote STUN/TURN TCP'
    sudo ufw allow 3478/udp comment 'Multidevice Remote STUN/TURN UDP'
    sudo ufw allow 49160:49200/udp comment 'Multidevice Remote Relay UDP'
    sudo ufw reload
    echo "[✓] Port berhasil dibuka di UFW."
else
    echo "[!] firewalld / ufw tidak terdeteksi atau tidak aktif."
    echo "[+] Mencoba mengonfigurasi iptables langsung..."
    sudo iptables -I INPUT -p tcp --dport 3478 -j ACCEPT 2>/dev/null || true
    sudo iptables -I INPUT -p udp --dport 3478 -j ACCEPT 2>/dev/null || true
    sudo iptables -I INPUT -p udp --dport 49160:49200 -j ACCEPT 2>/dev/null || true
    echo "[✓] Aturan iptables ditambahkan."
fi

echo ""
echo "PENTING: Jangan lupa membuka Ingress Rule di Oracle Cloud VCN Console!"
echo "Port: 3478 (TCP/UDP) dan 49160-49200 (UDP) dari 0.0.0.0/0"
