# --- Build stage: compiles the server from source (no Android SDK needed) ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY shared shared
COPY server server
RUN chmod +x gradlew && ./gradlew :server:installDist -PserverOnly \
    --no-daemon --console=plain \
    -Dorg.gradle.jvmargs=-Xmx768m \
    -Pkotlin.compiler.execution.strategy=in-process

# --- Runtime stage: small JRE image with just the server ---
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S entanglement && adduser -S entanglement -G entanglement \
    && mkdir /data && chown entanglement:entanglement /data
COPY --from=build /src/server/build/install/entanglement-server /app
USER entanglement
ENV ENTANGLEMENT_DB=/data/entanglement.db \
    PORT=8080 \
    JAVA_OPTS="-Xmx128m -XX:+UseSerialGC"
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=60s --timeout=5s --start-period=20s \
    CMD wget -qO- http://127.0.0.1:8080/health || exit 1
CMD ["/app/bin/entanglement-server"]
