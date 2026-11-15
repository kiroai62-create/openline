#!/usr/bin/env bash
# Creates a separate deployment for one client: its own copy of the code, database, settings,
# API keys (.env), system service and web address. Nothing is shared with other clients.
#
#   sudo bash deploy/new-client.sh 32teeth "32 Teeth Hospital" 32teeth.duckdns.org [booking-url]
#
# Afterwards open https://<domain>/ to create the client's console account.
set -euo pipefail

usage() { echo "Usage: sudo bash $0 <short-name> \"<Business name>\" <domain> [booking-url]"; exit 1; }
[ $# -ge 3 ] || usage
SLUG=$1; NAME=$2; DOMAIN=$3; BOOKING=${4:-}
[[ "$SLUG" =~ ^[a-z0-9][a-z0-9-]{1,30}$ ]] || { echo "short-name: lowercase letters, digits and -, 2-31 chars"; exit 1; }
[[ "$DOMAIN" =~ ^[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ ]] || { echo "domain looks wrong: $DOMAIN"; exit 1; }
[[ "$NAME" != *'"'* && "$NAME" != *$'\n'* ]] || { echo "Business name can't contain quotes or new lines"; exit 1; }
[ -z "$BOOKING" ] || [[ "$BOOKING" =~ ^https?://[^[:space:]\"]+$ ]] || { echo "booking-url must start with http(s)://"; exit 1; }

ROOT=${ROOT:-/opt/receptionist}
REPO=${REPO:-https://github.com/kiroai62-create/openline.git}
BRANCH=${BRANCH:-main}
SKIP_SERVICES=${SKIP_SERVICES:-0}   # 1 = only create files (testing)
DIR="$ROOT/clients/$SLUG"
SERVICE="receptionist-$SLUG"

if [ "$SKIP_SERVICES" != 1 ]; then [ "$(id -u)" -eq 0 ] || { echo "Run as root: sudo bash $0 ..."; exit 1; }; fi
[ ! -e "$DIR" ] || { echo "$DIR already exists. Pick another short-name or remove it first."; exit 1; }

# Next free port from 8801, skipping ports used by other clients.
PORT=8801
while grep -qs "^PORT=$PORT$" "$ROOT"/clients/*/server/.env; do PORT=$((PORT + 1)); done

echo "==> Code for $NAME in $DIR"
mkdir -p "$ROOT/clients"
git clone -q --depth 1 --branch "$BRANCH" "$REPO" "$DIR"
(cd "$DIR/server" && npm ci --silent --no-audit --no-fund && npm run build --silent)

echo "==> Settings ($DIR/server/.env)"
API_KEY=$(node -e 'process.stdout.write(require("node:crypto").randomBytes(30).toString("base64url"))')
cat > "$DIR/server/.env" <<EOF
# $NAME: this client's own deployment. Keep this file secret.
PORT=$PORT
HOST=127.0.0.1
HTTP_GATEWAY_API_KEY=$API_KEY
PUBLIC_URL=https://$DOMAIN
DATA_DIR=$DIR/server/data
CLIENT_NAME="$NAME"
BOOKING_URL=$BOOKING
# This client's own AI key (its own Google account). More providers: console Settings.
# GEMINI_API_KEY=
# This client's own WhatsApp gateway.
# WHATSAPP_GATEWAY_URL=
# WHATSAPP_API_SECRET=
EOF
chmod 600 "$DIR/server/.env"

if [ "$SKIP_SERVICES" = 1 ]; then echo "SKIP_SERVICES=1: files only (port $PORT)"; exit 0; fi

id receptionist >/dev/null 2>&1 || useradd --system --home "$ROOT" --shell /usr/sbin/nologin receptionist
chown -R receptionist:receptionist "$DIR"

echo "==> Service $SERVICE"
cat > "/etc/systemd/system/$SERVICE.service" <<EOF
[Unit]
Description=AI receptionist: $NAME
After=network-online.target
Wants=network-online.target

[Service]
User=receptionist
WorkingDirectory=$DIR/server
ExecStart=$(command -v node) --disable-warning=ExperimentalWarning --env-file=$DIR/server/.env dist/index.js
Restart=always
RestartSec=3
NoNewPrivileges=true
ProtectSystem=full
ReadWritePaths=$DIR/server/data

[Install]
WantedBy=multi-user.target
EOF
mkdir -p "$DIR/server/data" && chown receptionist:receptionist "$DIR/server/data"
systemctl daemon-reload
systemctl enable --now "$SERVICE" >/dev/null

echo "==> HTTPS for $DOMAIN"
cat > "/etc/caddy/sites/$SLUG.caddy" <<EOF
$DOMAIN {
	reverse_proxy 127.0.0.1:$PORT
}
EOF
systemctl reload caddy

sleep 2
if curl -fsS "http://127.0.0.1:$PORT/health" >/dev/null; then STATE="running"; else STATE="NOT RUNNING (sudo journalctl -u $SERVICE -n 50)"; fi
cat <<EOF

$NAME is set up ($STATE).
  Console:        https://$DOMAIN/   (the HTTPS certificate can take a minute)
  Setup key:      $API_KEY
                  (asked once when you create the console account; also in $DIR/server/.env)
  Service:        sudo systemctl status $SERVICE
  Logs:           sudo journalctl -u $SERVICE -f
  Update later:   sudo bash $DIR/deploy/update-client.sh $SLUG

Next: create the account, add this client's Gemini key and providers in Settings, its WhatsApp
gateway, fill in Playground > Assistant, build its app (GitHub Actions > Android APK) and pair
its phone under Connection.
EOF
