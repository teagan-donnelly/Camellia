#!/usr/bin/env bash
# Builds deploy/lambda/dist/camellia-lambda.zip: run.sh + the Spring Boot jar.
# The zip is larger than Lambda's 50 MB direct-upload limit, so deploy it through an S3 object (the unzipped size
# must stay under 250 MB including layers).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
dist="$here/dist"

(cd "$root/backend" && ./mvnw -q -DskipTests package)
jar="$(find "$root/backend/target" -maxdepth 1 -name 'camellia-backend-*.jar' ! -name '*.original' | head -1)"
[ -n "$jar" ] || { echo "No backend jar found in backend/target" >&2; exit 1; }

stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
cp "$jar" "$stage/camellia-backend.jar"
cp "$here/run.sh" "$stage/run.sh"
chmod 755 "$stage/run.sh"
mkdir -p "$dist"
rm -f "$dist/camellia-lambda.zip"
(cd "$stage" && zip -q -X "$dist/camellia-lambda.zip" run.sh camellia-backend.jar)

unzipped=$(( $(stat -c %s "$stage/camellia-backend.jar") + $(stat -c %s "$stage/run.sh") ))
echo "Built $dist/camellia-lambda.zip ($(du -h "$dist/camellia-lambda.zip" | cut -f1); unzipped $((unzipped / 1048576)) MB of 250 MB)"
