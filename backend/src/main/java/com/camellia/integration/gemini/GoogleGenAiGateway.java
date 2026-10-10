package com.camellia.integration.gemini;

import com.camellia.config.CamelliaProperties;
import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.types.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Real Gemini calls through the official Google Gen AI Java SDK (com.google.genai).
 * The API key stays on the server; it is never sent to the browser or logged.
 */
@Component
public class GoogleGenAiGateway implements GeminiGateway {

    private static final Logger log = LoggerFactory.getLogger(GoogleGenAiGateway.class);

    private final CamelliaProperties.Gemini cfg;
    private volatile Client client;

    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private volatile LocalDateTime lastSuccessAt;
    private volatile String lastError;
    private volatile String lastOperation;

    public GoogleGenAiGateway(CamelliaProperties props) {
        this.cfg = props.getGemini();
        if (!isConfigured()) {
            log.warn("GEMINI_API_KEY is not set — Gemini features will report 'not configured' (no simulated AI answers are produced).");
        } else {
            log.info("Gemini configured with model {}", cfg.getModel());
        }
    }

    @Override
    public boolean isConfigured() {
        return cfg.getApiKey() != null && !cfg.getApiKey().isBlank();
    }

    @Override
    public String model() {
        return cfg.getModel();
    }

    public record Stats(boolean configured, String model, long calls, long failures, LocalDateTime lastSuccessAt,
                        String lastError, String lastOperation) {}

    public Stats stats() {
        return new Stats(isConfigured(), cfg.getModel(), calls.get(), failures.get(), lastSuccessAt, lastError, lastOperation);
    }

    private Client client() {
        if (!isConfigured()) throw GeminiException.notConfigured();
        Client c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    client = Client.builder()
                            .apiKey(cfg.getApiKey().trim())
                            .httpOptions(HttpOptions.builder().timeout(cfg.getTimeoutSeconds() * 1000).build())
                            .build();
                }
                c = client;
            }
        }
        return c;
    }

    @Override
    public Reply generateJson(Request request) {
        Client c = client();
        List<Content> contents = request.turns().stream()
                .map(t -> Content.builder().role(t.role()).parts(Part.fromText(t.text())).build())
                .toList();
        GenerateContentConfig config = GenerateContentConfig.builder()
                .systemInstruction(Content.fromParts(Part.fromText(request.systemInstruction())))
                .responseMimeType("application/json")
                .responseJsonSchema(request.jsonSchema())
                .candidateCount(1)
                .build();
        long start = System.currentTimeMillis();
        calls.incrementAndGet();
        lastOperation = request.operation();
        try {
            GenerateContentResponse response = c.models.generateContent(cfg.getModel(), contents, config);
            String text = response.text();
            if (text == null || text.isBlank()) {
                throw new GeminiException(HttpStatus.BAD_GATEWAY, "GEMINI_EMPTY_RESPONSE",
                        "Gemini returned an empty response (it may have been blocked by safety settings). Try rephrasing.");
            }
            Integer in = response.usageMetadata().flatMap(GenerateContentResponseUsageMetadata::promptTokenCount).orElse(null);
            Integer out = response.usageMetadata().flatMap(GenerateContentResponseUsageMetadata::candidatesTokenCount).orElse(null);
            long latency = System.currentTimeMillis() - start;
            lastSuccessAt = LocalDateTime.now();
            lastError = null;
            log.info("Gemini {} ok in {} ms (model {}, tokens in/out {}/{})", request.operation(), latency, cfg.getModel(), in, out);
            return new Reply(text, cfg.getModel(), in, out, latency);
        } catch (GeminiException e) {
            recordFailure(e.getMessage());
            throw e;
        } catch (ApiException e) {
            GeminiException mapped = map(e);
            recordFailure(mapped.getMessage());
            throw mapped;
        } catch (GenAiIOException e) {
            recordFailure("Network/timeout: " + e.getMessage());
            throw new GeminiException(HttpStatus.GATEWAY_TIMEOUT, "GEMINI_UNREACHABLE",
                    "Could not reach Gemini within " + cfg.getTimeoutSeconds() + " s. Check network access and try again.", e);
        } catch (RuntimeException e) {
            recordFailure(e.getClass().getSimpleName() + ": " + e.getMessage());
            throw new GeminiException(HttpStatus.BAD_GATEWAY, "GEMINI_ERROR", "Gemini request failed: " + e.getMessage(), e);
        }
    }

    private GeminiException map(ApiException e) {
        int code = e.code();
        if (code == 429) {
            return new GeminiException(HttpStatus.TOO_MANY_REQUESTS, "GEMINI_RATE_LIMITED",
                    "Gemini rate limit or quota reached. Wait a moment and retry.", e);
        }
        if (code == 401 || code == 403) {
            return new GeminiException(HttpStatus.BAD_GATEWAY, "GEMINI_AUTH_FAILED",
                    "Gemini rejected the API key (HTTP " + code + "). Verify GEMINI_API_KEY.", e);
        }
        if (code == 404) {
            return new GeminiException(HttpStatus.BAD_GATEWAY, "GEMINI_MODEL_NOT_FOUND",
                    "Gemini model '" + cfg.getModel() + "' is not available to this key. Set GEMINI_MODEL to a supported model.", e);
        }
        if (code == 400) {
            return new GeminiException(HttpStatus.BAD_GATEWAY, "GEMINI_BAD_REQUEST", "Gemini rejected the request: " + e.message(), e);
        }
        return new GeminiException(HttpStatus.BAD_GATEWAY, "GEMINI_UPSTREAM_ERROR",
                "Gemini returned HTTP " + code + ": " + e.message(), e);
    }

    private void recordFailure(String message) {
        failures.incrementAndGet();
        lastError = message;
    }
}
