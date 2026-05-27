# ── Stage 1: Build the JAR ────────────────────────────────────────────────────
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Cache dependencies first (layer caching — only re-downloads on pom.xml change)
COPY server/pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

# Build
COPY server/src ./src
RUN mvn -q -DskipTests package

# ── Stage 2: Runtime image ────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app

# Install yt-dlp (requires python3 for some extractors)
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates python3 python3-pip \
    && curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp \
       -o /usr/local/bin/yt-dlp \
    && chmod a+rx /usr/local/bin/yt-dlp \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/target/echolingo-server-*.jar /app/echolingo-server.jar

# Default env — override at runtime via docker run -e or compose environment:
ENV PORT=3001
ENV ECHOLINGO_CACHE_DIR=/app/cache
ENV ECHOLINGO_YTDLP_PATH=/usr/local/bin/yt-dlp
ENV ECHOLINGO_YTDLP_TIMEOUT_MS=60000
ENV ECHOLINGO_YTDLP_PROXY_LIST=
ENV ECHOLINGO_DEEPL_KEY=
ENV ECHOLINGO_GROQ_API_KEY=

EXPOSE 3001
VOLUME ["/app/cache"]

ENTRYPOINT ["java", "-Xms128m", "-Xmx512m", "-jar", "/app/echolingo-server.jar"]

