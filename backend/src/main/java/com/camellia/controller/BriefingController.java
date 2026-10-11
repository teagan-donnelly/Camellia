package com.camellia.controller;

import com.camellia.briefing.BriefingService;
import com.camellia.briefing.BriefingService.*;
import com.camellia.security.DemoUser;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Camellia Briefing. Audio is served only through {@code /audio}, which re-checks that the requesting (simulated) user
 * generated the briefing; it is never written to a public directory or exposed through a public URL.
 */
@RestController
@RequestMapping("/api/briefings")
public class BriefingController {

    private final BriefingService briefings;

    public BriefingController(BriefingService briefings) {
        this.briefings = briefings;
    }

    @GetMapping("/shifts")
    public List<ShiftView> shifts(DemoUser user) {
        return briefings.shifts(user);
    }

    @PostMapping("/generate")
    public BriefingView generate(DemoUser user, @RequestBody GenerateRequest req) {
        return briefings.generate(user, req);
    }

    @GetMapping("/{id}")
    public BriefingView get(DemoUser user, @PathVariable String id) {
        return briefings.get(user, id);
    }

    /** Transcript with segment timings and resolved source citations (same view, kept as its own endpoint). */
    @GetMapping("/{id}/transcript")
    public BriefingView transcript(DemoUser user, @PathVariable String id) {
        return briefings.get(user, id);
    }

    @GetMapping("/{id}/audio")
    public ResponseEntity<byte[]> audio(DemoUser user, @PathVariable String id) {
        BriefingService.Audio a = briefings.audio(user, id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(a.contentType() == null ? "audio/mpeg" : a.contentType()))
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header("Accept-Ranges", "none")
                .body(a.bytes());
    }
}
