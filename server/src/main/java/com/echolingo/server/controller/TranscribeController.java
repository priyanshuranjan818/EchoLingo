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
            String transcript = groqService.transcribeAudio(tempFile.toString(), req.lang());
            return ResponseEntity.ok(new TranscribeResponse(transcript));
        } finally {
            try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
        }
    }

    public record TranscribeRequest(
            @NotBlank String audioBase64,
            String lang          // optional hint; defaults to "de" if blank
    ) {
        // Normalise null/blank lang to "de"
        public String lang() {
            return (lang == null || lang.isBlank()) ? "de" : lang;
        }
    }

    public record TranscribeResponse(String transcript) {}
}
