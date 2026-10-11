package com.camellia.ai;

import com.camellia.config.CamelliaProperties;
import com.camellia.integration.gemini.GeminiGateway.Turn;
import com.camellia.repository.ConversationRepository;
import com.camellia.repository.ConversationRepository.StoredTurn;
import com.camellia.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Conversation history, bound to exactly one context (user + patient / flag / alert / search result set).
 * A conversation can never be continued against a different context, which prevents cross-patient leakage.
 *
 * <p>History is stored in the database rather than in memory, so a follow-up question still has its context when it
 * is served by a different (stateless) API instance. Turns older than {@link #TTL} are ignored and deleted.</p>
 */
@Component
public class ConversationStore {

    static final Duration TTL = Duration.ofHours(2);
    private static final Duration CLEANUP_EVERY = Duration.ofMinutes(15);
    private static final int MAX_ID_LENGTH = 48;

    public static final class Conversation {
        final String id;
        final String contextKey;
        final List<Turn> turns;
        private final ConversationRepository repo;
        private int nextTurnNo;

        Conversation(String id, String contextKey, List<Turn> turns, int nextTurnNo, ConversationRepository repo) {
            this.id = id;
            this.contextKey = contextKey;
            this.turns = new ArrayList<>(turns);
            this.nextTurnNo = nextTurnNo;
            this.repo = repo;
        }

        public String id() {
            return id;
        }

        public synchronized List<Turn> recent(int maxTurns) {
            int from = Math.max(0, turns.size() - maxTurns * 2);
            return List.copyOf(turns.subList(from, turns.size()));
        }

        public synchronized void append(Turn user, Turn model) {
            LocalDateTime now = LocalDateTime.now();
            repo.append(List.of(
                    new StoredTurn(id, nextTurnNo, contextKey, user.role(), user.text(), now),
                    new StoredTurn(id, nextTurnNo + 1, contextKey, model.role(), model.text(), now)));
            nextTurnNo += 2;
            turns.add(user);
            turns.add(model);
        }
    }

    private final ConversationRepository repo;
    private final int loadLimit;
    private final AtomicLong nextCleanupAt = new AtomicLong();

    public ConversationStore(ConversationRepository repo, CamelliaProperties props) {
        this.repo = repo;
        this.loadLimit = Math.max(1, props.getGemini().getMaxHistoryTurns()) * 2;
    }

    public Conversation getOrCreate(String conversationId, String contextKey) {
        cleanupIfDue();
        if (conversationId != null && !conversationId.isBlank() && conversationId.length() <= MAX_ID_LENGTH) {
            List<StoredTurn> newestFirst = repo.latest(conversationId, loadLimit);
            LocalDateTime cutoff = LocalDateTime.now().minus(TTL);
            if (!newestFirst.isEmpty() && newestFirst.getFirst().createdAt() != null
                    && newestFirst.getFirst().createdAt().isAfter(cutoff)) {
                if (newestFirst.stream().anyMatch(t -> !t.contextKey().equals(contextKey))) {
                    throw new ApiException(HttpStatus.CONFLICT, "CONTEXT_MISMATCH",
                            "This conversation belongs to a different patient or context. Start a new conversation.");
                }
                List<Turn> turns = newestFirst.reversed().stream().map(t -> new Turn(t.speaker(), t.text())).toList();
                return new Conversation(conversationId, contextKey, turns, newestFirst.getFirst().turnNo() + 1, repo);
            }
        }
        return new Conversation("CONV-" + UUID.randomUUID(), contextKey, List.of(), 1, repo);
    }

    /** Deletes expired turns, at most once per {@link #CLEANUP_EVERY} per instance. */
    private void cleanupIfDue() {
        long now = System.currentTimeMillis();
        long due = nextCleanupAt.get();
        if (now >= due && nextCleanupAt.compareAndSet(due, now + CLEANUP_EVERY.toMillis())) {
            repo.deleteOlderThan(LocalDateTime.now().minus(TTL));
        }
    }
}
