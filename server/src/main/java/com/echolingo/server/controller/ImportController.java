package com.echolingo.server.controller;

import com.echolingo.server.model.VideoMeta;
import com.echolingo.server.service.ImportService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/import")
public class ImportController {
    private final ImportService importService;

    public ImportController(ImportService importService) {
        this.importService = importService;
    }

    @PostMapping
    VideoMeta importVideo(@RequestBody @Valid ImportRequest request) {
        return importService.importVideo(request.url(), request.sourceLang(), request.targetLang());
    }

    public record ImportRequest(
            @NotBlank String url,
            String sourceLang,
            String targetLang
    ) {
    }
}
