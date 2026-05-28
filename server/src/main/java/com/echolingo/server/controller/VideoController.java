package com.echolingo.server.controller;

import com.echolingo.server.exception.AppError;
import com.echolingo.server.service.YtdlpService;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/video")
public class VideoController {
    private final YtdlpService ytdlpService;
    private final OkHttpClient httpClient;

    public VideoController(YtdlpService ytdlpService) {
        this.ytdlpService = ytdlpService;
        this.httpClient = new OkHttpClient.Builder()
                .followRedirects(true)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
    }

    @GetMapping("/{videoId}/stream")
    ResponseEntity<StreamingResponseBody> stream(
            @PathVariable String videoId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {
        String streamUrl = ytdlpService.resolveStreamUrl(videoId);

        Request.Builder requestBuilder = new Request.Builder()
                .url(streamUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/124.0.0.0 Safari/537.36")
                .header("Accept", "video/webm,video/mp4,video/*,*/*")
                .header("Connection", "keep-alive");
        if (rangeHeader != null && !rangeHeader.isBlank()) {
            requestBuilder.header(HttpHeaders.RANGE, rangeHeader);
        }

        Response upstream;
        try {
            upstream = httpClient.newCall(requestBuilder.build()).execute();
        } catch (IOException e) {
            throw new AppError(HttpStatus.BAD_GATEWAY, "Could not reach video stream: " + e.getMessage());
        }

        if (!upstream.isSuccessful() || upstream.body() == null) {
            closeQuietly(upstream);
            throw new AppError(HttpStatus.BAD_GATEWAY,
                    "Video stream request failed with HTTP " + upstream.code() + ".");
        }

        ResponseBody upstreamBody = upstream.body();
        StreamingResponseBody body = outputStream -> {
            try (upstream; InputStream input = upstreamBody.byteStream()) {
                StreamUtils.copy(input, outputStream);
            }
        };

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        copyHeader(upstream, headers, HttpHeaders.CONTENT_LENGTH);
        copyHeader(upstream, headers, HttpHeaders.CONTENT_RANGE);
        copyHeader(upstream, headers, HttpHeaders.ETAG);
        copyHeader(upstream, headers, HttpHeaders.LAST_MODIFIED);
        String contentType = upstream.header(HttpHeaders.CONTENT_TYPE);
        headers.setContentType(contentType == null
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(contentType));

        HttpStatus status = upstream.code() == 206 ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK;
        return ResponseEntity.status(status).headers(headers).body(body);
    }

    private static void copyHeader(Response source, HttpHeaders target, String name) {
        String value = source.header(name);
        if (value != null && !value.isBlank()) {
            target.set(name, value);
        }
    }

    private static void closeQuietly(Response response) {
        try {
            response.close();
        } catch (Exception ignored) {
        }
    }
}
