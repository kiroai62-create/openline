#!/usr/bin/env bash
# Shows every client deployment on this server: address, port, and whether it is running.
set -uo pipefail
ROOT=${ROOT:-/opt/receptionist}
printf "%-16s %-34s %-6s %s\n" CLIENT ADDRESS PORT STATE
for DIR in "$ROOT"/clients/*/; do
  [ -f "$DIR/server/.env" ] || continue
  SLUG=$(basename "$DIR")
  PORT=$(grep '^PORT=' "$DIR/server/.env" | cut -d= -f2)
  URL=$(grep '^PUBLIC_URL=' "$DIR/server/.env" | cut -d= -f2)
  if curl -fsS -m 3 "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then STATE=running; else STATE=down; fi
  printf "%-16s %-34s %-6s %s\n" "$SLUG" "$URL" "$PORT" "$STATE"
done
