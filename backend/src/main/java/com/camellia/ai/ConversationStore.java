package com.camellia.ai;

import com.camellia.integration.gemini.GeminiGateway.Turn;
import com.camellia.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory conversation history, bound to exactly one context (patient / flag / alert / search result set).
 * A conversation can never be continued against a different context, which prevents cross-patient leakage.
 */
@Component
public class ConversationStore {

    static final Duration TTL = Duration.ofHours(2);
    static final int MAX_CONVERSATIONS = 500;

    public static final class Conversation {
        final String id;
        final String contextKey;
        final List<Turn> turns = new ArrayList<>();
        Instant lastAccess = Instant.now();

        Conversation(String id, String contextKey) {
            this.id = id;
            this.contextKey = contextKey;
        }

        public String id() {
            return id;
        }

        public synchronized List<Turn> recent(int maxTurns) {
            int from = Math.max(0, turns.size() - maxTurns * 2);
            return List.copyOf(turns.subList(from, turns.size()));
        }

        public synchronized void append(Turn user, Turn model) {
            turns.add(user);
            turns.add(model);
            lastAccess = Instant.now();
        }
    }

    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();

    public Conversation getOrCreate(String conversationId, String contextKey) {
        evictExpired();
        if (conversationId != null && !conversationId.isBlank()) {
            Conversation c = conversations.get(conversationId);
            if (c != null) {
                if (!c.contextKey.equals(contextKey)) {
                    throw new ApiException(HttpStatus.CONFLICT, "CONTEXT_MISMATCH",
                            "This conversation belongs to a different patient or context. Start a new conversation.");
                }
                c.lastAccess = Instant.now();
                return c;
            }
        }
        if (conversations.size() >= MAX_CONVERSATIONS) {
            conversations.values().stream().min(Comparator.comparing(c -> c.lastAccess))
                    .ifPresent(oldest -> conversations.remove(oldest.id));
        }
        Conversation c = new Conversation("CONV-" + UUID.randomUUID(), contextKey);
        conversations.put(c.id, c);
        return c;
    }

    private void evictExpired() {
        Instant cutoff = Instant.now().minus(TTL);
        conversations.values().removeIf(c -> c.lastAccess.isBefore(cutoff));
    }
}
