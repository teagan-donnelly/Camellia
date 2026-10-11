package com.camellia.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.camellia.repository.Jdbc.*;

/** Camellia Briefing metadata and source references (audio bytes are stored outside the database). */
@Repository
public class BriefingRepository {

    public record Briefing(String briefingId, String shiftId, String userId, String mode, String status, String statusDetail,
                           String sourceFingerprint, Integer patientCount, Integer openAlertCount, Integer excludedCount,
                           String scriptJson, String transcript, String geminiModel, String ttsProvider, String ttsModel,
                           String voiceId, String audioSha256, String audioPath, Long audioBytes, String audioContentType,
                           Double durationSeconds, Integer ttsCharacters, Integer scriptAttempts, LocalDateTime createdAt,
                           LocalDateTime completedAt) {

        public Briefing withStatus(String newStatus, String detail) {
            return new Briefing(briefingId, shiftId, userId, mode, newStatus, detail, sourceFingerprint, patientCount,
                    openAlertCount, excludedCount, scriptJson, transcript, geminiModel, ttsProvider, ttsModel, voiceId,
                    audioSha256, audioPath, audioBytes, audioContentType, durationSeconds, ttsCharacters, scriptAttempts,
                    createdAt, completedAt);
        }
    }

    public record Reference(String briefingId, int segmentNo, String patientId, String sourceType, String sourceId) {}

    private static final RowMapper<Briefing> MAPPER = (rs, i) -> new Briefing(rs.getString("BRIEFING_ID"),
            rs.getString("SHIFT_ID"), rs.getString("USER_ID"), rs.getString("MODE"), rs.getString("STATUS"),
            rs.getString("STATUS_DETAIL"), rs.getString("SOURCE_FINGERPRINT"), integer(rs, "PATIENT_COUNT"),
            integer(rs, "OPEN_ALERT_COUNT"), integer(rs, "EXCLUDED_COUNT"), rs.getString("SCRIPT_JSON"),
            rs.getString("TRANSCRIPT"), rs.getString("GEMINI_MODEL"), rs.getString("TTS_PROVIDER"), rs.getString("TTS_MODEL"),
            rs.getString("VOICE_ID"), rs.getString("AUDIO_SHA256"), rs.getString("AUDIO_PATH"), longValue(rs.getBigDecimal("AUDIO_BYTES")),
            rs.getString("AUDIO_CONTENT_TYPE"), dbl(rs, "DURATION_SECONDS"), integer(rs, "TTS_CHARACTERS"),
            integer(rs, "SCRIPT_ATTEMPTS"), ldt(rs, "CREATED_AT"), ldt(rs, "COMPLETED_AT"));

    private static final RowMapper<Reference> REF_MAPPER = (rs, i) -> new Reference(rs.getString("BRIEFING_ID"),
            integer(rs, "SEGMENT_NO"), rs.getString("PATIENT_ID"), rs.getString("SOURCE_TYPE"), rs.getString("SOURCE_ID"));

    private static final String COLUMNS = """
            BRIEFING_ID, SHIFT_ID, USER_ID, MODE, STATUS, STATUS_DETAIL, SOURCE_FINGERPRINT, PATIENT_COUNT, OPEN_ALERT_COUNT,
            EXCLUDED_COUNT, SCRIPT_JSON, TRANSCRIPT, GEMINI_MODEL, TTS_PROVIDER, TTS_MODEL, VOICE_ID, AUDIO_SHA256, AUDIO_PATH,
            AUDIO_BYTES, AUDIO_CONTENT_TYPE, DURATION_SECONDS, TTS_CHARACTERS, SCRIPT_ATTEMPTS, CREATED_AT, COMPLETED_AT""";

    private static final int[] TYPES = {S, S, S, S, S, S, S, I, I, I, S, S, S, S, S, S, S, S, java.sql.Types.BIGINT, S, N, I, I, TS, TS};

    private final JdbcTemplate jdbc;

    public BriefingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Object[] values(Briefing b) {
        return new Object[]{b.briefingId(), b.shiftId(), b.userId(), b.mode(), b.status(), clip(b.statusDetail(), 2000),
                b.sourceFingerprint(), b.patientCount(), b.openAlertCount(), b.excludedCount(), b.scriptJson(), b.transcript(),
                b.geminiModel(), b.ttsProvider(), b.ttsModel(), b.voiceId(), b.audioSha256(), b.audioPath(), b.audioBytes(),
                b.audioContentType(), b.durationSeconds(), b.ttsCharacters(), b.scriptAttempts(), ts(b.createdAt()),
                ts(b.completedAt())};
    }

    public void insert(Briefing b) {
        jdbc.update("INSERT INTO BRIEFINGS (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                values(b), TYPES);
    }

    /** Replaces every mutable column of an existing briefing. */
    public void update(Briefing b) {
        Object[] v = values(b);
        Object[] reordered = new Object[v.length];
        System.arraycopy(v, 1, reordered, 0, v.length - 1);
        reordered[v.length - 1] = b.briefingId();
        int[] types = new int[TYPES.length];
        System.arraycopy(TYPES, 1, types, 0, TYPES.length - 1);
        types[TYPES.length - 1] = S;
        jdbc.update("""
                UPDATE BRIEFINGS SET SHIFT_ID = ?, USER_ID = ?, MODE = ?, STATUS = ?, STATUS_DETAIL = ?, SOURCE_FINGERPRINT = ?,
                    PATIENT_COUNT = ?, OPEN_ALERT_COUNT = ?, EXCLUDED_COUNT = ?, SCRIPT_JSON = ?, TRANSCRIPT = ?, GEMINI_MODEL = ?,
                    TTS_PROVIDER = ?, TTS_MODEL = ?, VOICE_ID = ?, AUDIO_SHA256 = ?, AUDIO_PATH = ?, AUDIO_BYTES = ?,
                    AUDIO_CONTENT_TYPE = ?, DURATION_SECONDS = ?, TTS_CHARACTERS = ?, SCRIPT_ATTEMPTS = ?, CREATED_AT = ?,
                    COMPLETED_AT = ?
                WHERE BRIEFING_ID = ?""", reordered, types);
    }

    public Optional<Briefing> find(String briefingId) {
        return jdbc.query("SELECT * FROM BRIEFINGS WHERE BRIEFING_ID = ?", MAPPER, briefingId).stream().findFirst();
    }

    public List<Briefing> forUser(String userId, int limit) {
        int safe = Math.max(1, Math.min(limit, 100));
        return jdbc.query("SELECT * FROM BRIEFINGS WHERE USER_ID = ? ORDER BY CREATED_AT DESC LIMIT " + safe, MAPPER, userId);
    }

    /** Newest finished briefing for the same user, shift, mode and source snapshot (reused instead of regenerating). */
    public Optional<Briefing> reusable(String userId, String shiftId, String mode, String fingerprint) {
        return jdbc.query("""
                SELECT * FROM BRIEFINGS WHERE USER_ID = ? AND SHIFT_ID = ? AND MODE = ? AND SOURCE_FINGERPRINT = ?
                  AND STATUS IN ('READY', 'SCRIPT_ONLY') ORDER BY CREATED_AT DESC LIMIT 1""",
                MAPPER, userId, shiftId, mode, fingerprint).stream().findFirst();
    }

    public void replaceReferences(String briefingId, List<Reference> refs) {
        jdbc.update("DELETE FROM BRIEFING_SOURCE_REFERENCES WHERE BRIEFING_ID = ?", briefingId);
        batch(jdbc, "INSERT INTO BRIEFING_SOURCE_REFERENCES (BRIEFING_ID, SEGMENT_NO, PATIENT_ID, SOURCE_TYPE, SOURCE_ID) VALUES (?, ?, ?, ?, ?)",
                refs.stream().map(r -> new Object[]{r.briefingId(), r.segmentNo(), r.patientId(), r.sourceType(), r.sourceId()}).toList(),
                S, I, S, S, S);
    }

    public List<Reference> references(String briefingId) {
        return jdbc.query("SELECT * FROM BRIEFING_SOURCE_REFERENCES WHERE BRIEFING_ID = ? ORDER BY SEGMENT_NO, SOURCE_TYPE, SOURCE_ID",
                REF_MAPPER, briefingId);
    }

    private static Long longValue(BigDecimal v) {
        return v == null ? null : v.longValue();
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
