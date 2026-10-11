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
import java.util.ArrayList;
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
    private static final long PRIMARY_COOLDOWN_MS = 180_000;
    private volatile long primaryCooldownUntil;
    private final java.util.Set<String> thinkingUnsupported = java.util.concurrent.ConcurrentHashMap.newKeySet();

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

    public String fallbackModel() {
        return cfg.getFallbackModel();
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
                            // Retries are handled here (with model fallback), so the SDK's own retry loop is disabled.
                            .httpOptions(HttpOptions.builder().timeout(cfg.getTimeoutSeconds() * 1000)
                                    .retryOptions(HttpRetryOptions.builder().attempts(1).build()).build())
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
        List<String> models = new ArrayList<>(List.of(cfg.getModel()));
        String fallback = cfg.getFallbackModel();
        if (fallback != null && !fallback.isBlank() && !fallback.trim().equals(cfg.getModel())) models.add(fallback.trim());

        lastOperation = request.operation();
        if (models.size() > 1 && System.currentTimeMillis() < primaryCooldownUntil) {
            models.removeFirst();   // primary recently overloaded / rate limited: go straight to the fallback
        }
        Budget budget = new Budget(System.currentTimeMillis(), cfg.getRequestBudgetSeconds());
        long attemptMs = cfg.getTimeoutSeconds() * 1000L;
        GeminiException last = null;
        for (String model : models) {
            for (int attempt = 0; attempt <= Math.max(0, cfg.getMaxRetries()); attempt++) {
                if (last != null && !budget.fits(System.currentTimeMillis(), attemptMs)) {
                    log.warn("Gemini {}: request budget of {} s used up; not starting another attempt",
                            request.operation(), cfg.getRequestBudgetSeconds());
                    throw last;
                }
                try {
                    return callOnce(c, model, contents, request);
                } catch (GeminiException e) {
                    last = e;
                    recordFailure(e.getMessage());
                    // An overloaded model often stops answering instead of returning 503. Waiting another full timeout
                    // on the same model rarely helps, so a timeout moves straight on to the fallback model.
                    if (isTimeout(e) && model.equals(cfg.getModel()) && models.size() > 1) {
                        log.warn("Gemini {} on {} did not respond within {} s; switching to fallback model",
                                request.operation(), model, cfg.getTimeoutSeconds());
                        break;
                    }
                    if (!isTransient(e)) throw e;
                    if (attempt < cfg.getMaxRetries()) {
                        long backoff = 1500L * (1L << attempt);
                        if (!budget.fits(System.currentTimeMillis(), backoff + attemptMs)) break;   // no time to retry: try the fallback
                        log.warn("Gemini {} on {} ({}); retrying in {} ms", request.operation(), model, e.code(), backoff);
                        sleep(backoff);
                    }
                }
            }
            if (models.size() > 1 && model.equals(cfg.getModel())) {
                primaryCooldownUntil = System.currentTimeMillis() + PRIMARY_COOLDOWN_MS;
                log.warn("Gemini model {} unavailable after retries; using fallback model {} for the next {} s",
                        model, models.getLast(), PRIMARY_COOLDOWN_MS / 1000);
            }
        }
        throw last;
    }

    private Reply callOnce(Client c, String model, List<Content> contents, Request request) {
        GenerateContentConfig.Builder builder = GenerateContentConfig.builder()
                .systemInstruction(Content.fromParts(Part.fromText(request.systemInstruction())))
                .responseMimeType("application/json")
                .responseJsonSchema(request.jsonSchema())
                .candidateCount(1);
        String level = cfg.getThinkingLevel();
        boolean useThinking = level != null && !level.isBlank() && !thinkingUnsupported.contains(model);
        if (useThinking) builder.thinkingConfig(ThinkingConfig.builder().thinkingLevel(level.trim().toUpperCase(java.util.Locale.ROOT)).build());
        long start = System.currentTimeMillis();
        calls.incrementAndGet();
        try {
            GenerateContentResponse response = c.models.generateContent(model, contents, builder.build());
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
            log.info("Gemini {} ok in {} ms (model {}, tokens in/out {}/{})", request.operation(), latency, model, in, out);
            return new Reply(text, model, in, out, latency);
        } catch (ApiException e) {
            if (useThinking && e.code() == 400 && String.valueOf(e.message()).toLowerCase(java.util.Locale.ROOT).contains("thinking")) {
                log.warn("Model {} rejected thinking level '{}'; retrying without it", model, level);
                thinkingUnsupported.add(model);
                return callOnce(c, model, contents, request);
            }
            throw map(e, model);
        } catch (GenAiIOException e) {
            throw new GeminiException(HttpStatus.GATEWAY_TIMEOUT, "GEMINI_UNREACHABLE",
                    "Gemini (" + model + ") did not respond within " + cfg.getTimeoutSeconds()
                            + " s. It may be overloaded, or network access may be blocked. Try again in a moment.", e);
        } catch (GeminiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new GeminiException(HttpStatus.BAD_GATEWAY, "GEMINI_ERROR", "Gemini request failed: " + e.getMessage(), e);
        }
    }

    /**
     * Time accounting for one request (all retries and the fallback model). An attempt only starts while a full
     * per-attempt timeout still fits, so the request ends before an API gateway in front of it gives up.
     * The first attempt always runs.
     */
    static final class Budget {
        private final long deadlineMs;

        Budget(long nowMs, int budgetSeconds) {
            this.deadlineMs = budgetSeconds > 0 ? nowMs + budgetSeconds * 1000L : Long.MAX_VALUE;
        }

        boolean fits(long nowMs, long neededMs) {
            return deadlineMs == Long.MAX_VALUE || nowMs + neededMs <= deadlineMs;
        }
    }

    static boolean isTimeout(GeminiException e) {
        return e.code().equals("GEMINI_UNREACHABLE");
    }

    static boolean isTransient(GeminiException e) {
        return e.code().equals("GEMINI_RATE_LIMITED") || e.code().equals("GEMINI_OVERLOADED");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private GeminiException map(ApiException e, String model) {
        int code = e.code();
        if (code == 503 || code == 500) {
            return new GeminiException(HttpStatus.SERVICE_UNAVAILABLE, "GEMINI_OVERLOADED",
                    "Gemini (" + model + ") is temporarily overloaded. Please retry in a moment.", e);
        }
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
                    "Gemini model '" + model + "' is not available to this key. Set GEMINI_MODEL to a supported model.", e);
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
