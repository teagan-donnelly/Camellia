#!/bin/sh
# Handler of the Lambda zip package (the same package serves camellia-api and camellia-jobs; environment variables
# choose the Snowflake identity and whether the jobs endpoint is on). Lambda Web Adapter, added as a layer and enabled
# with AWS_LAMBDA_EXEC_WRAPPER=/opt/bootstrap, runs this script, waits until AWS_LWA_READINESS_CHECK_PATH answers,
# then proxies requests to port 8080. The JVM flags mirror pom.xml's spring-boot:run jvmArguments, which java -jar
# does not apply. TieredStopAtLevel=1 trades peak throughput for a faster start.
exec java -XX:+TieredCompilation -XX:TieredStopAtLevel=1 \
     --add-opens=java.base/java.nio=ALL-UNNAMED -Duser.timezone=UTC \
     -jar "${LAMBDA_TASK_ROOT:-/var/task}/camellia-backend.jar"
