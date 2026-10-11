#!/usr/bin/env bash
# Publishes the built frontend to Vercel as a static site (project "camellia").
# The browser only ever talks to the Vercel domain: /api/* is proxied to the Camellia backend given as $1, so the
# HttpOnly session cookie and relative /api calls work unchanged. Run `npx vercel@60.1.3 login` once first.
# The proxy forwards the browser's Origin header, so the backend must allow the Vercel site, e.g. in backend/.env.local:
#   CAMELLIA_CORS_ORIGINS=http://localhost:5173,http://127.0.0.1:5173,https://camellia-snowy.vercel.app
#
#   deploy/vercel/deploy.sh https://<public-backend-host>
set -euo pipefail

BACKEND="${1:?Usage: deploy/vercel/deploy.sh https://<public-backend-host>}"
BACKEND="${BACKEND%/}"
case "$BACKEND" in https://*) ;; *) echo "The backend URL must start with https://" >&2; exit 1 ;; esac

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# A fixed staging folder keeps the Vercel project link (.vercel/) between deploys. Git-ignored.
STAGE="$ROOT/deploy/vercel/.stage/camellia"

(cd "$ROOT/frontend" && npm run build)
mkdir -p "$STAGE"
find "$STAGE" -mindepth 1 -maxdepth 1 ! -name .vercel -exec rm -rf {} +
cp -r "$ROOT/frontend/dist/." "$STAGE/"

# Static files are served first; then /api goes to the backend and every other path to the single-page app.
cat > "$STAGE/vercel.json" <<JSON
{
  "\$schema": "https://openapi.vercel.sh/vercel.json",
  "rewrites": [
    { "source": "/api/:path*", "destination": "$BACKEND/api/:path*" },
    { "source": "/(.*)", "destination": "/index.html" }
  ],
  "headers": [
    { "source": "/assets/(.*)", "headers": [{ "key": "Cache-Control", "value": "public, max-age=31536000, immutable" }] },
    { "source": "/(.*)", "headers": [{ "key": "X-Content-Type-Options", "value": "nosniff" }, { "key": "Referrer-Policy", "value": "same-origin" }] }
  ]
}
JSON

cd "$STAGE"
npx --yes vercel@60.1.3 deploy --prod --yes
