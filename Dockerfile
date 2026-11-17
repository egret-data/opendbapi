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
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests -pl opendbapi-app -am package \
    && cp opendbapi-app/target/opendbapi-app-*.jar /opendbapi.jar \
    && cd / && java -Djarmode=tools -jar /opendbapi.jar extract --layers --launcher --destination /layers

FROM eclipse-temurin:21.0.5_11-jre-alpine
RUN addgroup -S -g 10001 opendbapi && adduser -S -u 10001 -G opendbapi -h /app opendbapi
WORKDIR /app
COPY --from=app /layers/dependencies/ ./
COPY --from=app /layers/spring-boot-loader/ ./
COPY --from=app /layers/snapshot-dependencies/ ./
COPY --from=app /layers/application/ ./
USER 10001:10001
EXPOSE 8520
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    OPENDBAPI_SECURE_COOKIES=true
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s \
    CMD wget -q -O /dev/null http://127.0.0.1:8520/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
