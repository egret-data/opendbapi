# syntax=docker/dockerfile:1.7
# OpenDBAPI: one image with the API and the management UI.
#   docker build -t opendbapi .

FROM node:24.11.0-bookworm-slim AS ui
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npx vite build

FROM maven:3.9.9-eclipse-temurin-21 AS app
WORKDIR /src/backend
COPY backend/ ./
COPY --from=ui /src/frontend/dist /src/frontend/dist
# Maven Central answers 429 under load; retry downloads instead of failing the build.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests -Daether.connector.http.retryHandler.count=6 \
      -Daether.connector.http.retryHandler.interval=10000 -pl opendbapi-app,opendbapi-cli -am package \
    && cp opendbapi-app/target/opendbapi-app-*.jar /opendbapi.jar \
    && cp opendbapi-cli/target/opendbapi-cli.jar /opendbapi-cli.jar \
    && cd / && java -Djarmode=tools -jar /opendbapi.jar extract --layers --launcher --destination /layers

FROM eclipse-temurin:21.0.5_11-jre-alpine
RUN addgroup -S -g 10001 opendbapi && adduser -S -u 10001 -G opendbapi -h /app opendbapi
WORKDIR /app
COPY --from=app /layers/dependencies/ ./
COPY --from=app /layers/spring-boot-loader/ ./
COPY --from=app /layers/snapshot-dependencies/ ./
COPY --from=app /layers/application/ ./
COPY --from=app /opendbapi-cli.jar /app/cli/opendbapi-cli.jar
# `opendbapi plan|apply|export ...` runs the command line (spec §15.2); anything else starts the server.
COPY --chmod=755 <<'EOF' /usr/local/bin/opendbapi
#!/bin/sh
exec env -u JAVA_TOOL_OPTIONS java -XX:TieredStopAtLevel=1 -jar /app/cli/opendbapi-cli.jar "$@"
EOF
COPY --chmod=755 <<'EOF' /usr/local/bin/docker-entrypoint
#!/bin/sh
case "$1" in
  export|plan|apply|help|--help|-h) exec opendbapi "$@" ;;
esac
exec java org.springframework.boot.loader.launch.JarLauncher "$@"
EOF
USER 10001:10001
EXPOSE 8520
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    OPENDBAPI_SECURE_COOKIES=true
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s \
    CMD wget -q -O /dev/null http://127.0.0.1:8520/actuator/health/readiness || exit 1
ENTRYPOINT ["docker-entrypoint"]
