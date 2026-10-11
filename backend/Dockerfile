# Camellia backend container (used by the Render web service; see deploy/render/README.md).
# Build context: backend/.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package \
 && cp "$(find target -maxdepth 1 -name 'camellia-backend-*.jar' ! -name '*.original' | head -1)" /src/camellia-backend.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --create-home camellia
WORKDIR /app
COPY --from=build /src/camellia-backend.jar ./camellia-backend.jar
COPY docker-entrypoint.sh ./docker-entrypoint.sh
# The shared backend/.env (non-secret defaults and the hackathon keys the team chose to commit). Environment
# variables set on the host override it.
COPY .env ./.env
RUN chmod 755 docker-entrypoint.sh && mkdir -p data/documents && chown -R camellia:camellia /app
USER camellia

# Sized for a 512 MB instance (Render free plan): bounded heap, serial GC and C1-only JIT keep memory and startup low.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -XX:ReservedCodeCacheSize=48m -XX:MaxMetaspaceSize=160m -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["./docker-entrypoint.sh"]
