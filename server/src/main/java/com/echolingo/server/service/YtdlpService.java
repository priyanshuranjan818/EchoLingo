package com.echolingo.server.service;

import com.echolingo.server.config.AppConfig;
import com.echolingo.server.exception.AppError;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class YtdlpService {
    private final AppConfig config;

    public YtdlpService(AppConfig config) {
        this.config = config;
    }

    public String resolveStreamUrl(String videoId) {
        validateVideoId(videoId);
        List<String> args = new ArrayList<>(List.of(
                config.ytdlpPath(),
                "-g",
                "-f",
                "best[ext=mp4]/best",
                "https://www.youtube.com/watch?v=" + videoId
        ));

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        try {
            Process proc = pb.start();
            boolean finished = proc.waitFor(config.ytdlpTimeoutMs(), TimeUnit.MILLISECONDS);
            String output = readAll(proc);
            if (!finished) {
                proc.destroyForcibly();
                throw new AppError(HttpStatus.GATEWAY_TIMEOUT, "yt-dlp timed out while resolving the video stream.");
            }
            if (proc.exitValue() != 0) {
                throw new AppError(HttpStatus.BAD_GATEWAY, "yt-dlp failed to resolve the video stream.");
            }
            return output.lines()
                    .filter(line -> line.startsWith("http://") || line.startsWith("https://"))
                    .findFirst()
                    .orElseThrow(() -> new AppError(HttpStatus.BAD_GATEWAY, "yt-dlp did not return a playable stream URL."));
        } catch (IOException e) {
            throw new AppError(HttpStatus.BAD_GATEWAY, "Could not start yt-dlp. Check ECHOLINGO_YTDLP_PATH.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppError(HttpStatus.INTERNAL_SERVER_ERROR, "Stream resolution was interrupted.");
        }
    }

    private static String readAll(Process proc) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString().trim();
        }
    }

    private static void validateVideoId(String videoId) {
        if (!videoId.matches("[A-Za-z0-9_-]{11}")) {
            throw new AppError(HttpStatus.BAD_REQUEST, "Invalid YouTube video ID.");
        }
    }
}
