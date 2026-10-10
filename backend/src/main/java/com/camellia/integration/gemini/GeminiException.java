package com.camellia.integration.gemini;

import org.springframework.http.HttpStatus;

/** A Gemini failure translated into an HTTP status and a message that is safe to display. */
public class GeminiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public GeminiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public GeminiException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static GeminiException notConfigured() {
        return new GeminiException(HttpStatus.SERVICE_UNAVAILABLE, "GEMINI_NOT_CONFIGURED",
                "Gemini is not configured. Set GEMINI_API_KEY in backend/.env (see .env.example) and restart the backend.");
    }
}
