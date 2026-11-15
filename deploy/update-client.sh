#!/usr/bin/env bash
# Updates one client's deployment (or all) to the latest code and restarts it.
#   sudo bash deploy/update-client.sh 32teeth
#   sudo bash deploy/update-client.sh all
# A restart ends calls in progress on that client: run it at night.
set -euo pipefail
[ $# -eq 1 ] || { echo "Usage: sudo bash $0 <short-name|all>"; exit 1; }
[ "$(id -u)" -eq 0 ] || { echo "Run as root"; exit 1; }
ROOT=${ROOT:-/opt/receptionist}

if [ "$1" = all ]; then
  mapfile -t SLUGS < <(ls "$ROOT/clients")
else
  SLUGS=("$1")
fi

mkdir -p "$ROOT/backups"
for SLUG in "${SLUGS[@]}"; do
  DIR="$ROOT/clients/$SLUG"
  [ -d "$DIR/server" ] || { echo "no client $SLUG"; continue; }
  echo "==> $SLUG"
  sqlite3 "$DIR/server/data/gateway.db" ".backup $ROOT/backups/$SLUG-before-update-$(date +%F-%H%M).db" 2>/dev/null || true
  AS="sudo -u receptionist env HOME=$DIR"
  $AS git -C "$DIR" pull -q --ff-only
  (cd "$DIR/server" && $AS npm ci --silent --no-audit --no-fund && $AS npm run build --silent)
  systemctl restart "receptionist-$SLUG"
  sleep 2
  PORT=$(grep '^PORT=' "$DIR/server/.env" | cut -d= -f2)
  curl -fsS "http://127.0.0.1:$PORT/health" >/dev/null && echo "    running" || echo "    NOT RUNNING: sudo journalctl -u receptionist-$SLUG -n 50"
done
