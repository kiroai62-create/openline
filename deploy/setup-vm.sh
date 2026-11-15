#!/usr/bin/env bash
# One-time setup of a fresh Ubuntu 22.04/24.04 server (Oracle Cloud Always Free, Google Cloud
# e2-micro, any VPS). Installs Node.js 22, Caddy (HTTPS), sqlite3, opens ports 80/443 and adds a
# nightly backup of every client's database.
#
#   curl -fsSL https://raw.githubusercontent.com/kiroai62-create/openline/main/deploy/setup-vm.sh | sudo bash
set -euo pipefail

[ "$(id -u)" -eq 0 ] || { echo "Run as root: sudo bash $0"; exit 1; }
ROOT=${ROOT:-/opt/receptionist}

echo "==> Packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq git curl ca-certificates gnupg sqlite3 debian-keyring debian-archive-keyring apt-transport-https >/dev/null

if ! command -v node >/dev/null || [ "$(node -p 'process.versions.node.split(".")[0]')" -lt 22 ]; then
  echo "==> Node.js 22"
  curl -fsSL https://deb.nodesource.com/setup_22.x | bash - >/dev/null
  apt-get install -y -qq nodejs >/dev/null
fi
echo "    node $(node -v)"

if ! command -v caddy >/dev/null; then
  echo "==> Caddy"
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' > /etc/apt/sources.list.d/caddy-stable.list
  apt-get update -qq && apt-get install -y -qq caddy >/dev/null
fi
mkdir -p /etc/caddy/sites
if ! grep -q "import sites/\*" /etc/caddy/Caddyfile 2>/dev/null; then
  printf '# Each client deployment adds a file in /etc/caddy/sites/.\nimport sites/*.caddy\n' > /etc/caddy/Caddyfile
fi
systemctl enable --now caddy >/dev/null
systemctl reload caddy || true

echo "==> Firewall: allow 80 and 443"
if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
  ufw allow 80/tcp >/dev/null; ufw allow 443/tcp >/dev/null
fi
# Oracle Cloud Ubuntu images block everything but SSH in iptables.
if iptables -L INPUT -n 2>/dev/null | grep -q "REJECT"; then
  for port in 80 443; do
    iptables -C INPUT -p tcp --dport "$port" -j ACCEPT 2>/dev/null || iptables -I INPUT 5 -p tcp --dport "$port" -j ACCEPT
  done
  command -v netfilter-persistent >/dev/null && netfilter-persistent save >/dev/null || true
fi

echo "==> Nightly backups (03:00, kept 14 days) in $ROOT/backups"
mkdir -p "$ROOT/clients" "$ROOT/backups"
cat > /etc/cron.d/receptionist-backup <<EOF
0 3 * * * root for d in $ROOT/clients/*/server/data/gateway.db; do c=\$(basename \$(dirname \$(dirname \$(dirname \$d)))); sqlite3 "\$d" ".backup $ROOT/backups/\$c-\$(date +\\%F).db"; done; find $ROOT/backups -name '*.db' -mtime +14 -delete
EOF

echo
echo "Done. Next, for each client:"
echo "  sudo bash deploy/new-client.sh <short-name> \"<Business name>\" <domain>"
echo "Oracle Cloud: also open ports 80 and 443 in the VCN Security List (see DEPLOYMENT.md)."
