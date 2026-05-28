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

    private static final String COOKIES_PATH = "/app/cookies.txt";

    private final AppConfig config;

    public YtdlpService(AppConfig config) {
        this.config = config;
    }

    public String resolveStreamUrl(String videoId) {
        validateVideoId(videoId);
        List<String> args = new ArrayList<>(List.of(
                config.ytdlpPath(),
                "-g",
                "-f", "best[ext=mp4]/best",
                "https://www.youtube.com/watch?v=" + videoId
        ));
        addProxyAndCookies(args);
        return runAndCapture(args, videoId, "stream URL").lines()
                .filter(l -> l.startsWith("http://") || l.startsWith("https://"))
                .findFirst()
                .orElseThrow(() -> new AppError(HttpStatus.BAD_GATEWAY,
                        "yt-dlp did not return a playable stream URL."));
    }

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
        addProxyAndCookies(args);

        try {
            runAndCapture(args, videoId, "subtitle download");
        } catch (AppError e) {
            cleanupDir(tempDir);
            return List.of();
        }

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

    /**
     * Downloads audio as m4a to a temp file and returns its path.
     * Caller is responsible for deleting the file after use.
     */
    public Path downloadAudio(String videoId) {
        validateVideoId(videoId);
        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("echolingo_audio_");
        } catch (IOException e) {
            throw new AppError(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not create temp dir for audio download.");
        }

        String outputTemplate = tempDir.resolve("%(id)s.%(ext)s").toString();
        List<String> args = new ArrayList<>(List.of(
                config.ytdlpPath(),
                "-f", "bestaudio[ext=m4a]/bestaudio/best",
                "-x",
                "--audio-format", "mp3",
                "--audio-quality", "0",
                "--output", outputTemplate,
                "https://www.youtube.com/watch?v=" + videoId
        ));
        addProxyAndCookies(args);

        runAndCapture(args, videoId, "audio download");

        try {
            return Files.list(tempDir)
                    .filter(p -> p.toString().endsWith(".mp3") || p.toString().endsWith(".m4a")
                            || p.toString().endsWith(".webm") || p.toString().endsWith(".ogg"))
                    .findFirst()
                    .orElseThrow(() -> new AppError(HttpStatus.BAD_GATEWAY,
                            "yt-dlp did not produce an audio file for video: " + videoId));
        } catch (IOException e) {
            throw new AppError(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not list audio temp dir: " + e.getMessage());
        }
    }

    private void addProxyAndCookies(List<String> args) {
        int insertAt = args.size() - 1;

        if (java.nio.file.Files.exists(java.nio.file.Path.of(COOKIES_PATH))) {
            args.add(insertAt, "--cookies");
            args.add(insertAt + 1, COOKIES_PATH);
            insertAt += 2;
        }

        String proxyList = config.ytdlpProxyList();
        if (proxyList != null && !proxyList.isBlank()) {
            String proxy = proxyList.split(",")[0].trim();
            args.add(insertAt, "--proxy");
            args.add(insertAt + 1, proxy);
        }
    }

    private String runAndCapture(List<String> args, String videoId, String operation) {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        try {
            Process proc = pb.start();
            String output = readAll(proc);
            boolean finished = proc.waitFor(config.ytdlpTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!finished) {
                proc.destroyForcibly();
                throw new AppError(HttpStatus.GATEWAY_TIMEOUT,
                        "yt-dlp timed out during " + operation + " for video: " + videoId);
            }
            if (proc.exitValue() != 0) {
                throw new AppError(HttpStatus.BAD_GATEWAY,
                        "yt-dlp failed during " + operation + " (exit " + proc.exitValue() + "): " + output);
            }
            return output;
        } catch (IOException e) {
            throw new AppError(HttpStatus.BAD_GATEWAY,
                    "Could not start yt-dlp: " + e.getMessage());
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
