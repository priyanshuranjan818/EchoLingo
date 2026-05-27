package com.echolingo.server.controller;

import com.echolingo.server.model.Cue;
import com.echolingo.server.service.ImportService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/subtitles")
public class SubtitlesController {
    private final ImportService importService;

    public SubtitlesController(ImportService importService) {
        this.importService = importService;
    }

    @GetMapping("/{videoId}/{lang}")
    List<Cue> getSubtitles(@PathVariable String videoId, @PathVariable String lang) {
        return importService.getSubtitles(videoId, lang);
    }
}
