#!/bin/sh
# Starts the backend in the container. The Snowflake driver reads the private key from a file, so the key is taken from
# a Render secret file (mounted under /etc/secrets) or, failing that, from the SNOWFLAKE_PRIVATE_KEY variable.
set -e

secret_key=""
for f in /etc/secrets/snowflake_key.p8 /etc/secrets/*.p8 /etc/secrets/*.pem /etc/secrets/snowflake_key*; do
  if [ -r "$f" ]; then secret_key="$f"; break; fi
done

if [ -n "$secret_key" ]; then
  echo "Using Snowflake private key from secret file $secret_key"
  export SNOWFLAKE_PRIVATE_KEY_FILE="$secret_key"
elif [ -n "$SNOWFLAKE_PRIVATE_KEY" ]; then
  key=$(mktemp /tmp/snowflake-XXXXXX.p8)
  printf '%s\n' "$SNOWFLAKE_PRIVATE_KEY" > "$key"
  chmod 600 "$key"
  export SNOWFLAKE_PRIVATE_KEY_FILE="$key"
  unset SNOWFLAKE_PRIVATE_KEY
else
  echo "No Snowflake private key found. Files in /etc/secrets: $(ls -A /etc/secrets 2>&1 | tr '\n' ' ')" >&2
fi

# The JVM flags mirror pom.xml's spring-boot:run jvmArguments, which java -jar does not apply.
exec java $JAVA_OPTS --add-opens=java.base/java.nio=ALL-UNNAMED -Duser.timezone=UTC -jar /app/camellia-backend.jar
