package com.echolingo.server.controller;

import com.echolingo.server.model.VideoMeta;
import com.echolingo.server.service.ImportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/meta")
public class MetaController {
    private final ImportService importService;

    public MetaController(ImportService importService) {
        this.importService = importService;
    }

    @GetMapping("/{videoId}")
    VideoMeta getMeta(@PathVariable String videoId) {
        return importService.getMeta(videoId);
    }
}
