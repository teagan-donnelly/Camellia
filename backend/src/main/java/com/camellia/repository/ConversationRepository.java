package com.camellia.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

import static com.camellia.repository.Jdbc.*;

/** Assistant conversation turns (AI_CONVERSATION_TURNS). */
@Repository
public class ConversationRepository {

    public record StoredTurn(String conversationId, int turnNo, String contextKey, String speaker, String text,
                             LocalDateTime createdAt) {}

    private static final RowMapper<StoredTurn> MAPPER = (rs, i) -> new StoredTurn(
            rs.getString("CONVERSATION_ID"), integer(rs, "TURN_NO"), rs.getString("CONTEXT_KEY"), rs.getString("SPEAKER"),
            rs.getString("MESSAGE_TEXT"), ldt(rs, "CREATED_AT"));

    private final JdbcTemplate jdbc;

    public ConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The newest turns of a conversation, newest first. */
    public List<StoredTurn> latest(String conversationId, int limit) {
        int safe = Math.max(1, Math.min(limit, 200));
        return jdbc.query("SELECT * FROM AI_CONVERSATION_TURNS WHERE CONVERSATION_ID = ? ORDER BY TURN_NO DESC LIMIT " + safe,
                MAPPER, conversationId);
    }

    public void append(List<StoredTurn> turns) {
        batch(jdbc, """
                INSERT INTO AI_CONVERSATION_TURNS (CONVERSATION_ID, TURN_NO, CONTEXT_KEY, SPEAKER, MESSAGE_TEXT, CREATED_AT)
                VALUES (?, ?, ?, ?, ?, ?)""",
                turns.stream().map(t -> new Object[]{t.conversationId(), t.turnNo(), t.contextKey(), t.speaker(), t.text(),
                        ts(t.createdAt())}).toList(),
                S, I, S, S, S, TS);
    }

    public int deleteOlderThan(LocalDateTime cutoff) {
        return jdbc.update("DELETE FROM AI_CONVERSATION_TURNS WHERE CREATED_AT < ?", new Object[]{ts(cutoff)}, new int[]{TS});
    }
}
