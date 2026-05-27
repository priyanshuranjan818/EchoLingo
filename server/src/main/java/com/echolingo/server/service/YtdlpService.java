package com.echolingo.server.service;

import com.echolingo.server.config.AppConfig;
import com.echolingo.server.exception.AppError;
import com.echolingo.server.model.Cue;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    // ---- Stream URL ---------------------------------------------------------

    public String resolveStreamUrl(String videoId) {
        validateVideoId(videoId);
        List<String> args = new ArrayList<>(List.of(
                config.ytdlpPath(),
                "-g",
                "-f", "best[ext=mp4]/best",
                "https://www.youtube.com/watch?v=" + videoId
        ));
        return runAndCapture(args, videoId, "stream URL").lines()
                .filter(l -> l.startsWith("http://") || l.startsWith("https://"))
                .findFirst()
                .orElseThrow(() -> new AppError(HttpStatus.BAD_GATEWAY,
                        "yt-dlp did not return a playable stream URL."));
    }

    // ---- Subtitle download (fallback) ---------------------------------------

    /**
     * Downloads VTT subtitles for the given video + language via yt-dlp,
     * then parses them with the provided VttParser.
     * Returns an empty list (not an exception) if no subtitles are found,
     * so the caller can try the next fallback.
     */
    public List<Cue> fetchSubtitles(String videoId, String lang, VttParser vttParser) {
        validateVideoId(videoId);
        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("echolingo_subs_");
        } catch (IOException e) {
            return List.of();
        }

        String outputTemplate = tempDir.resolve("%(id)s.%(ext)s").toString();
        List<String> args = new ArrayList<>(List.of(
                config.ytdlpPath(),
                "--write-auto-subs", "--write-subs",
                "--sub-langs", lang,
                "--skip-download",
                "--convert-subs", "vtt",
                "--output", outputTemplate,
                "--no-overwrites",
                "https://www.youtube.com/watch?v=" + videoId
        ));

        try {
            runAndCapture(args, videoId, "subtitle download"); // run, ignore stdout
        } catch (AppError e) {
            cleanupDir(tempDir);
            return List.of(); // yt-dlp failed — caller tries next fallback
        }

        // Find the generated .vtt file
        try {
            var vttFile = Files.list(tempDir)
                    .filter(p -> p.toString().endsWith(".vtt"))
                    .findFirst();

            if (vttFile.isEmpty()) {
                cleanupDir(tempDir);
                return List.of();
            }

            String vttText = Files.readString(vttFile.get(), StandardCharsets.UTF_8);
            cleanupDir(tempDir);
            return vttParser.parse(vttText);
        } catch (IOException e) {
            cleanupDir(tempDir);
            return List.of();
        }
    }

    // ---- Internals ----------------------------------------------------------

    private String runAndCapture(List<String> args, String videoId, String operation) {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        try {
            Process proc = pb.start();
            boolean finished = proc.waitFor(config.ytdlpTimeoutMs(), TimeUnit.MILLISECONDS);
            String output = readAll(proc);
            if (!finished) {
                proc.destroyForcibly();
                throw new AppError(HttpStatus.GATEWAY_TIMEOUT,
                        "yt-dlp timed out during " + operation + " for video: " + videoId);
            }
            if (proc.exitValue() != 0) {
                throw new AppError(HttpStatus.BAD_GATEWAY,
                        "yt-dlp failed during " + operation + " (exit " + proc.exitValue() + ").");
            }
            return output;
        } catch (IOException e) {
            throw new AppError(HttpStatus.BAD_GATEWAY,
                    "Could not start yt-dlp. Check ECHOLINGO_YTDLP_PATH: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppError(HttpStatus.INTERNAL_SERVER_ERROR,
                    "yt-dlp was interrupted during " + operation + ".");
        }
    }

    private static String readAll(Process proc) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
            return sb.toString().trim();
        }
    }

    private static void validateVideoId(String videoId) {
        if (!videoId.matches("[A-Za-z0-9_-]{11}")) {
            throw new AppError(HttpStatus.BAD_REQUEST, "Invalid YouTube video ID: " + videoId);
        }
    }

    private static void cleanupDir(Path dir) {
        try {
            Files.walk(dir)
                 .sorted(java.util.Comparator.reverseOrder())
                 .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }
}

