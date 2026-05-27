FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY server/pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY server/src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates python3 \
    && curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp -o /usr/local/bin/yt-dlp \
    && chmod a+rx /usr/local/bin/yt-dlp \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/target/echolingo-server-*.jar /app/echolingo-server.jar

ENV PORT=3001
ENV ECHOLINGO_CACHE_DIR=/app/cache
ENV ECHOLINGO_YTDLP_PATH=/usr/local/bin/yt-dlp

EXPOSE 3001
VOLUME ["/app/cache"]

ENTRYPOINT ["java", "-jar", "/app/echolingo-server.jar"]
