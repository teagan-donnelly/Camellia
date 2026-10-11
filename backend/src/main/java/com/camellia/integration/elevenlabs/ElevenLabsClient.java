package com.camellia.integration.elevenlabs;

import com.camellia.config.CamelliaProperties;
import com.camellia.ingestion.Json;
import com.camellia.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ElevenLabs text-to-speech through the documented REST API (https://elevenlabs.io/docs/api-reference):
 * <ul>
 *   <li>{@code POST /v1/text-to-speech/{voice_id}/with-timestamps} — audio (base64) plus per-character timing, which
 *       gives the real audio duration and per-segment start times.</li>
 *   <li>{@code GET /v2/voices} — used once to pick a default voice when {@code ELEVENLABS_VOICE_ID} is not set.</li>
 * </ul>
 * The API key is sent only from the backend in the {@code xi-api-key} header; it never reaches the browser or the logs.
 */
@Component
public class ElevenLabsClient {

    private static final Logger log = LoggerFactory.getLogger(ElevenLabsClient.class);

    private final CamelliaProperties.ElevenLabs cfg;
    private final HttpClient http;
    private volatile String resolvedVoiceId;
    private volatile String resolvedVoiceName;
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private volatile String lastError;

    public ElevenLabsClient(CamelliaProperties props) {
        this.cfg = props.getElevenlabs();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        if (!isConfigured()) log.warn("ELEVENLABS_API_KEY is not set — Camellia Briefing will produce transcripts without audio.");
    }

    public boolean isConfigured() {
        return cfg.getApiKey() != null && !cfg.getApiKey().isBlank();
    }

    public String modelId() {
        return cfg.getModelId();
    }

    public record Status(boolean configured, String modelId, String voiceId, String voiceName, String outputFormat, long calls,
                         long failures, String lastError) {}

    public Status status() {
        return new Status(isConfigured(), cfg.getModelId(), resolvedVoiceId != null ? resolvedVoiceId : blankToNull(cfg.getVoiceId()),
                resolvedVoiceName, cfg.getOutputFormat(), calls.get(), failures.get(), lastError);
    }

    public record Speech(byte[] audio, String contentType, double durationSeconds, String voiceId, String modelId,
                         int characters, List<Double> characterEndSeconds) {}

    /** Real API call. Throws {@link ApiException} with a specific code when not configured or when the request fails. */
    public Speech synthesize(String text) {
        if (!isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ELEVENLABS_NOT_CONFIGURED",
                    "ElevenLabs is not configured. Set ELEVENLABS_API_KEY in backend/.env and restart the backend.");
        }
        if (text == null || text.isBlank()) throw ApiException.badRequest("Nothing to synthesize");
        if (text.length() > cfg.getMaxCharacters()) {
            throw ApiException.badRequest("Script is " + text.length() + " characters; the configured limit is " + cfg.getMaxCharacters());
        }
        String voice = voiceId();
        ObjectNode body = Json.MAPPER.createObjectNode();
        body.put("text", text);
        body.put("model_id", cfg.getModelId());
        ObjectNode vs = body.putObject("voice_settings");
        vs.put("stability", cfg.getStability());
        vs.put("similarity_boost", cfg.getSimilarityBoost());
        vs.put("style", cfg.getStyle());
        vs.put("speed", cfg.getSpeed());
        vs.put("use_speaker_boost", true);
        URI uri = URI.create(base() + "/v1/text-to-speech/" + URLEncoder.encode(voice, StandardCharsets.UTF_8)
                + "/with-timestamps?output_format=" + URLEncoder.encode(cfg.getOutputFormat(), StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                .header("xi-api-key", cfg.getApiKey().trim()).header("Content-Type", "application/json")
                .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build();
        JsonNode res = send(req, "text-to-speech");
        String b64 = Json.text(res.path("audio_base64"));
        if (b64 == null) throw failure(HttpStatus.BAD_GATEWAY, "ELEVENLABS_EMPTY_AUDIO", "ElevenLabs returned no audio.");
        byte[] audio = Base64.getDecoder().decode(b64);
        List<Double> ends = new ArrayList<>();
        JsonNode align = res.path("alignment").isMissingNode() || res.path("alignment").isNull() ? res.path("normalized_alignment") : res.path("alignment");
        for (JsonNode n : align.path("character_end_times_seconds")) ends.add(n.asDouble());
        double duration = ends.isEmpty() ? estimateMp3Seconds(audio.length) : ends.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        return new Speech(audio, contentType(), duration, voice, cfg.getModelId(), text.length(), ends);
    }

    /** Configured voice, or the first premade voice tagged with the configured use case (e.g. conversational). */
    public String voiceId() {
        if (cfg.getVoiceId() != null && !cfg.getVoiceId().isBlank()) return cfg.getVoiceId().trim();
        if (resolvedVoiceId != null) return resolvedVoiceId;
        synchronized (this) {
            if (resolvedVoiceId != null) return resolvedVoiceId;
            String useCase = cfg.getVoiceUseCase() == null ? "" : cfg.getVoiceUseCase().trim();
            for (String query : List.of("?category=premade&page_size=20&use_cases=" + enc(useCase), "?category=premade&page_size=20",
                    "?page_size=20")) {
                HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/v2/voices" + query)).timeout(Duration.ofSeconds(30))
                        .header("xi-api-key", cfg.getApiKey().trim()).header("Accept", "application/json").GET().build();
                for (JsonNode v : send(req, "voices").path("voices")) {
                    String label = Json.text(v.path("labels").path("use_case"));
                    if (query.contains("use_cases") || useCase.isEmpty() || label == null || label.toLowerCase(Locale.ROOT).contains(useCase)) {
                        resolvedVoiceId = Json.text(v.path("voice_id"));
                        resolvedVoiceName = Json.text(v.path("name"));
                        if (resolvedVoiceId != null) {
                            log.info("ElevenLabs voice selected: {} ({})", resolvedVoiceName, resolvedVoiceId);
                            return resolvedVoiceId;
                        }
                    }
                }
            }
            throw failure(HttpStatus.BAD_GATEWAY, "ELEVENLABS_NO_VOICE",
                    "No ElevenLabs voice is available to this API key. Set ELEVENLABS_VOICE_ID.");
        }
    }

    private JsonNode send(HttpRequest req, String operation) {
        int attempts = Math.max(1, cfg.getMaxRetries() + 1);
        for (int attempt = 1; ; attempt++) {
            calls.incrementAndGet();
            try {
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                int code = res.statusCode();
                if (code >= 200 && code < 300) {
                    lastError = null;
                    return Json.parse(res.body());
                }
                boolean transient_ = code == 429 || code >= 500;
                String detail = detail(res.body());
                if (transient_ && attempt < attempts) {
                    long backoff = 2000L * attempt;
                    log.warn("ElevenLabs {} returned HTTP {} ({}); retrying in {} ms", operation, code, detail, backoff);
                    sleep(backoff);
                    continue;
                }
                throw switch (code) {
                    case 401, 403 -> failure(HttpStatus.BAD_GATEWAY, "ELEVENLABS_AUTH_FAILED",
                            "ElevenLabs rejected the API key or its permissions (HTTP " + code + "): " + detail);
                    case 422, 400 -> failure(HttpStatus.BAD_GATEWAY, "ELEVENLABS_BAD_REQUEST", "ElevenLabs rejected the request: " + detail);
                    case 429 -> failure(HttpStatus.TOO_MANY_REQUESTS, "ELEVENLABS_RATE_LIMITED",
                            "ElevenLabs rate limit or character quota reached: " + detail);
                    default -> failure(HttpStatus.BAD_GATEWAY, "ELEVENLABS_UPSTREAM_ERROR", "ElevenLabs returned HTTP " + code + ": " + detail);
                };
            } catch (IOException e) {
                if (attempt < attempts) {
                    sleep(2000L * attempt);
                    continue;
                }
                throw failure(HttpStatus.GATEWAY_TIMEOUT, "ELEVENLABS_UNREACHABLE", "Could not reach ElevenLabs: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw failure(HttpStatus.SERVICE_UNAVAILABLE, "ELEVENLABS_INTERRUPTED", "The ElevenLabs request was interrupted.");
            }
        }
    }

    private ApiException failure(HttpStatus status, String code, String message) {
        failures.incrementAndGet();
        lastError = message;
        return new ApiException(status, code, message);
    }

    private static String detail(String body) {
        if (body == null || body.isBlank()) return "no detail";
        try {
            JsonNode n = Json.parse(body);
            JsonNode d = n.path("detail");
            String msg = d.isString() ? d.asString() : Json.text(d.path("message"));
            if (msg == null && d.isArray() && !d.isEmpty()) msg = Json.text(d.get(0).path("msg"));
            if (msg != null) return msg.length() > 300 ? msg.substring(0, 300) : msg;
        } catch (RuntimeException ignored) {
            // not JSON
        }
        return body.length() > 200 ? body.substring(0, 200) : body;
    }

    private String contentType() {
        String f = cfg.getOutputFormat().toLowerCase(Locale.ROOT);
        if (f.startsWith("mp3")) return "audio/mpeg";
        if (f.startsWith("opus")) return "audio/ogg";
        if (f.startsWith("wav")) return "audio/wav";
        return "application/octet-stream";
    }

    /** Constant-bitrate estimate used only when the API returns no alignment (e.g. mp3_44100_128 → 128 kbit/s). */
    double estimateMp3Seconds(int bytes) {
        String[] parts = cfg.getOutputFormat().split("_");
        int kbps = parts.length >= 3 ? Integer.parseInt(parts[2]) : 128;
        return bytes * 8.0 / (kbps * 1000.0);
    }

    private String base() {
        String b = cfg.getBaseUrl() == null || cfg.getBaseUrl().isBlank() ? "https://api.elevenlabs.io" : cfg.getBaseUrl().trim();
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
