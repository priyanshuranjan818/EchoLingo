package com.echolingo.server.controller;

import com.echolingo.server.service.YtdlpService;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/video")
public class VideoController {
    private final YtdlpService ytdlpService;

    public VideoController(YtdlpService ytdlpService) {
        this.ytdlpService = ytdlpService;
    }

    @GetMapping("/{videoId}/stream")
    ResponseEntity<Void> stream(@PathVariable String videoId) {
        String streamUrl = ytdlpService.resolveStreamUrl(videoId);
        return ResponseEntity.status(302).location(URI.create(streamUrl)).build();
    }
}
