# ── Stage 1: Build the JAR ────────────────────────────────────────────────────
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY server/pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY server/src ./src
RUN mvn -q -DskipTests package

# ── Stage 2: Runtime image ────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates python3 python3-pip nodejs npm unzip ffmpeg \
    && curl -fsSL https://deno.land/install.sh | sh \
    && mv /root/.deno/bin/deno /usr/local/bin/deno \
    && pip3 install --no-cache-dir --break-system-packages yt-dlp yt-dlp-ejs \
    && ln -sf /usr/bin/nodejs /usr/bin/node 2>/dev/null || true \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/target/echolingo-server-*.jar /app/echolingo-server.jar

ENV PORT=3001
ENV ECHOLINGO_CACHE_DIR=/app/cache
ENV ECHOLINGO_YTDLP_PATH=yt-dlp
ENV ECHOLINGO_YTDLP_TIMEOUT_MS=120000
ENV ECHOLINGO_YTDLP_PROXY_LIST=
ENV ECHOLINGO_DEEPL_KEY=
ENV ECHOLINGO_GROQ_API_KEY=

EXPOSE 3001
VOLUME ["/app/cache"]
ENTRYPOINT ["java", "-Xms128m", "-Xmx512m", "-jar", "/app/echolingo-server.jar"]
