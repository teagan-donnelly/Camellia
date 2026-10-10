package com.camellia.integration.gemini;

import java.util.List;
import java.util.Map;

/** Boundary to the Gemini API, so prompt/validation logic can be tested without network calls. */
public interface GeminiGateway {

    record Turn(String role, String text) {
        public static Turn user(String text) { return new Turn("user", text); }
        public static Turn model(String text) { return new Turn("model", text); }
    }

    /**
     * @param systemInstruction behavioral rules
     * @param turns             conversation, oldest first; the last turn is the current user request
     * @param jsonSchema        JSON Schema the response must follow (structured output)
     */
    record Request(String operation, String systemInstruction, List<Turn> turns, Map<String, Object> jsonSchema) {}

    record Reply(String text, String model, Integer promptTokens, Integer outputTokens, long latencyMs) {}

    boolean isConfigured();

    String model();

    Reply generateJson(Request request);
}
