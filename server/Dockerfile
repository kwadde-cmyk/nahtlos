FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/libs.versions.toml gradle/libs.versions.toml
COPY core-model core-model
COPY server server
RUN chmod +x gradlew && ./gradlew :server:installDist --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/server/build/install/server /app
EXPOSE 8787
VOLUME /data
ENV PORT=8787
ENV SYNC_DB=/data/sync.db
ENV SYNC_TOKEN=dev-token-change-me
ENTRYPOINT ["/app/bin/server"]
