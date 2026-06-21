package com.coachlol.coach;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Expone el stream SSE al que se conecta el overlay para recibir consejos en vivo.
 */
@RestController
@RequestMapping("/api/advice")
public class AdviceController {

    private final AdviceBroadcaster broadcaster;

    public AdviceController(AdviceBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    // GET /api/advice/stream  -> canal SSE (lo consume overlay.html con EventSource)
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return broadcaster.register();
    }
}
