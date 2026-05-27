package com.echolingo.server.controller;

import com.echolingo.server.exception.AppError;
import com.echolingo.server.service.GroqService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/transcribe
 *
 * Accepts base64-encoded audio from the Android app (shadowing mode),
 * forwards it to Groq Whisper, and returns the transcript.
 *
 * BYOK: if the request includes a non-blank groqApiKey, it is used
 * instead of the server's configured key. This lets each user bring
 * their own free Groq key — the server needs no key of its own.
 */
@RestController
@RequestMapping("/api/transcribe")
public class TranscribeController {

    private final GroqService groqService;

    public TranscribeController(GroqService groqService) {
        this.groqService = groqService;
    }

    @PostMapping
    ResponseEntity<TranscribeResponse> transcribe(
            @RequestBody @Valid TranscribeRequest req) {

        byte[] audioBytes;
        try {
            audioBytes = Base64.getDecoder().decode(req.audioBase64());
        } catch (IllegalArgumentException e) {
            throw new AppError(HttpStatus.BAD_REQUEST, "audioBase64 is not valid Base64.");
        }

        Path tempFile;
        try {
            tempFile = Files.createTempFile("shadow_", ".m4a");
            Files.write(tempFile, audioBytes);
        } catch (IOException e) {
            throw new AppError(HttpStatus.INTERNAL_SERVER_ERROR, "Could not write temp audio file.");
        }

        try {
            // BYOK: use the key from the request if provided, else fall back to server config
            String keyToUse = (req.groqApiKey() != null && !req.groqApiKey().isBlank())
                    ? req.groqApiKey()
                    : null;   // null → GroqService uses its configured key

            String transcript = groqService.transcribeAudio(
                    tempFile.toString(), req.lang(), keyToUse);
            return ResponseEntity.ok(new TranscribeResponse(transcript));
        } finally {
            try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
        }
    }

    public record TranscribeRequest(
            @NotBlank String audioBase64,
            String lang,          // optional hint; normalised below
            String groqApiKey     // BYOK — null/blank = use server key
    ) {
        public String lang() {
            return (lang == null || lang.isBlank()) ? "de" : lang;
        }
        public String groqApiKey() {
            return groqApiKey == null ? "" : groqApiKey;
        }
    }

    public record TranscribeResponse(String transcript) {}
}
