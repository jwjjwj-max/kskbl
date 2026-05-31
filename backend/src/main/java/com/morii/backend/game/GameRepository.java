package com.morii.backend.game;

import com.morii.backend.game.GameDtos.CaseBundle;
import com.morii.backend.game.GameDtos.CaseInfo;
import com.morii.backend.game.GameDtos.DialogueTurnDto;
import com.morii.backend.game.GameDtos.EvidenceDto;
import com.morii.backend.game.GameDtos.NoteDto;
import com.morii.backend.game.GameDtos.SceneDto;
import com.morii.backend.game.GameDtos.SessionDto;
import com.morii.backend.game.GameDtos.SuspectDto;
import com.morii.backend.game.GameDtos.TimelineEventDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class GameRepository {
    public static final String CASE_ID = "wedding-poisoning";

    private final JdbcTemplate jdbc;
    private final JsonSupport json;

    public GameRepository(JdbcTemplate jdbc, JsonSupport json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public boolean caseExists() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM cases WHERE id = ?", Integer.class, CASE_ID);
        return count != null && count > 0;
    }

    public CaseBundle getCaseBundle() {
        CaseInfo caseInfo = jdbc.queryForObject(
                """
                        SELECT id, title, subtitle, description, opening_text, truth_summary
                        FROM cases
                        WHERE id = ?
                        """,
                this::mapCaseInfo,
                CASE_ID
        );
        return new CaseBundle(
                caseInfo,
                jdbc.query("SELECT * FROM case_scenes WHERE case_id = ? ORDER BY sort_order", this::mapScene, CASE_ID),
                jdbc.query("SELECT * FROM case_suspects WHERE case_id = ? ORDER BY sort_order", this::mapSuspect, CASE_ID),
                jdbc.query("SELECT * FROM case_evidence WHERE case_id = ? ORDER BY sort_order", this::mapEvidence, CASE_ID),
                jdbc.query("SELECT * FROM case_timeline_events WHERE case_id = ? ORDER BY sort_order", this::mapTimeline, CASE_ID)
        );
    }

    public UUID createSession(String playerName) {
        return jdbc.queryForObject(
                "INSERT INTO game_sessions(case_id, player_name) VALUES (?, ?) RETURNING id",
                UUID.class,
                CASE_ID,
                playerName
        );
    }

    public void unlockInitialEvidence(UUID sessionId) {
        List<String> ids = jdbc.queryForList(
                "SELECT id FROM case_evidence WHERE case_id = ? AND initial_unlocked = TRUE",
                String.class,
                CASE_ID
        );
        unlockEvidence(sessionId, ids, "system", null);
    }

    public void unlockEvidence(UUID sessionId, List<String> evidenceIds, String type, String byId) {
        for (String evidenceId : evidenceIds.stream().distinct().toList()) {
            jdbc.update(
                    """
                            INSERT INTO session_unlocked_evidence(session_id, evidence_id, unlocked_by_type, unlocked_by_id)
                            VALUES (?, ?, ?, ?)
                            ON CONFLICT (session_id, evidence_id) DO NOTHING
                            """,
                    sessionId,
                    evidenceId,
                    type,
                    byId
            );
        }
        touchSession(sessionId);
    }

    public List<String> getUnlockedEvidenceIds(UUID sessionId) {
        return jdbc.queryForList(
                """
                        SELECT evidence_id
                        FROM session_unlocked_evidence
                        WHERE session_id = ?
                        ORDER BY unlocked_at
                        """,
                String.class,
                sessionId
        );
    }

    public Optional<EvidenceDto> findEvidence(String id) {
        List<EvidenceDto> rows = jdbc.query("SELECT * FROM case_evidence WHERE id = ?", this::mapEvidence, id);
        return rows.stream().findFirst();
    }

    public Optional<SuspectDto> findSuspect(String id) {
        List<SuspectDto> rows = jdbc.query("SELECT * FROM case_suspects WHERE id = ?", this::mapSuspect, id);
        return rows.stream().findFirst();
    }

    public String getSuspectSystemPrompt(String suspectId) {
        return jdbc.queryForObject("SELECT system_prompt FROM case_suspects WHERE id = ?", String.class, suspectId);
    }

    public SessionDto getSession(UUID sessionId) {
        Map<String, Object> session = jdbc.queryForMap("SELECT * FROM game_sessions WHERE id = ?", sessionId);
        return new SessionDto(
                sessionId,
                String.valueOf(session.get("case_id")),
                String.valueOf(session.get("status")),
                String.valueOf(session.get("current_screen")),
                getUnlockedEvidenceIds(sessionId),
                getDialogue(sessionId),
                getNotes(sessionId)
        );
    }

    public List<DialogueTurnDto> getDialogue(UUID sessionId) {
        return jdbc.query(
                "SELECT * FROM dialogue_turns WHERE session_id = ? ORDER BY created_at ASC",
                this::mapDialogueTurn,
                sessionId
        );
    }

    public DialogueTurnDto insertDialogueTurn(
            UUID sessionId,
            String suspectId,
            String question,
            String shownEvidenceId,
            String answer,
            String mood,
            List<String> unlockedEvidenceIds,
            Map<String, Object> promptSnapshot,
            String modelName
    ) {
        UUID id = jdbc.queryForObject(
                """
                        INSERT INTO dialogue_turns(
                            session_id, suspect_id, question, shown_evidence_id, answer, mood,
                            unlocked_evidence_ids, prompt_snapshot, model_name
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                        RETURNING id
                        """,
                UUID.class,
                sessionId,
                suspectId,
                question,
                shownEvidenceId,
                answer,
                mood,
                json.write(unlockedEvidenceIds),
                json.write(promptSnapshot),
                modelName
        );
        touchSession(sessionId);
        return jdbc.queryForObject("SELECT * FROM dialogue_turns WHERE id = ?", this::mapDialogueTurn, id);
    }

    public void deleteNotes(UUID sessionId, String source) {
        jdbc.update("DELETE FROM investigation_notes WHERE session_id = ? AND source = ?", sessionId, source);
    }

    public NoteDto insertNote(
            UUID sessionId,
            String title,
            String body,
            String source,
            List<String> evidenceIds,
            List<String> suspectIds
    ) {
        UUID id = jdbc.queryForObject(
                """
                        INSERT INTO investigation_notes(
                            session_id, title, body, source, related_evidence_ids, related_suspect_ids
                        )
                        VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb)
                        RETURNING id
                        """,
                UUID.class,
                sessionId,
                title,
                body,
                source,
                json.write(evidenceIds),
                json.write(suspectIds)
        );
        return jdbc.queryForObject("SELECT * FROM investigation_notes WHERE id = ?", this::mapNote, id);
    }

    public List<NoteDto> getNotes(UUID sessionId) {
        return jdbc.query(
                "SELECT * FROM investigation_notes WHERE session_id = ? ORDER BY created_at DESC",
                this::mapNote,
                sessionId
        );
    }

    public void upsertVerdict(
            UUID sessionId,
            String accused,
            String keyItem,
            String reason,
            String ending,
            String score,
            String title,
            String body
    ) {
        jdbc.update(
                """
                        INSERT INTO verdicts(
                            session_id, accused_suspect_id, key_item_id, reason, ending, score, finale_title, finale_text
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (session_id)
                        DO UPDATE SET
                            accused_suspect_id = EXCLUDED.accused_suspect_id,
                            key_item_id = EXCLUDED.key_item_id,
                            reason = EXCLUDED.reason,
                            ending = EXCLUDED.ending,
                            score = EXCLUDED.score,
                            finale_title = EXCLUDED.finale_title,
                            finale_text = EXCLUDED.finale_text,
                            created_at = NOW()
                        """,
                sessionId,
                accused,
                keyItem,
                reason,
                ending,
                score,
                title,
                body
        );
        jdbc.update(
                """
                        UPDATE game_sessions
                        SET status = 'completed', completed_at = NOW(), updated_at = NOW()
                        WHERE id = ?
                        """,
                sessionId
        );
    }

    public void logAiCall(
            UUID sessionId,
            String purpose,
            String modelName,
            Map<String, Object> request,
            Map<String, Object> response,
            String error,
            Integer latencyMs
    ) {
        jdbc.update(
                """
                        INSERT INTO ai_call_logs(
                            session_id, purpose, model_name, request_json, response_json, error_message, latency_ms
                        )
                        VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                        """,
                sessionId,
                purpose,
                modelName,
                json.write(request == null ? Map.of() : request),
                json.write(response == null ? Map.of() : response),
                error,
                latencyMs
        );
    }

    public void updateScreen(UUID sessionId, String screen) {
        jdbc.update("UPDATE game_sessions SET current_screen = ?, updated_at = NOW() WHERE id = ?", screen, sessionId);
    }

    private void touchSession(UUID sessionId) {
        jdbc.update("UPDATE game_sessions SET updated_at = NOW() WHERE id = ?", sessionId);
    }

    private CaseInfo mapCaseInfo(ResultSet rs, int rowNum) throws SQLException {
        return new CaseInfo(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("subtitle"),
                rs.getString("description"),
                rs.getString("opening_text"),
                rs.getString("truth_summary")
        );
    }

    private SceneDto mapScene(ResultSet rs, int rowNum) throws SQLException {
        return new SceneDto(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("asset_path"),
                rs.getString("description"),
                rs.getInt("sort_order")
        );
    }

    private SuspectDto mapSuspect(ResultSet rs, int rowNum) throws SQLException {
        return new SuspectDto(
                rs.getString("id"),
                rs.getString("scene_id"),
                rs.getString("name"),
                rs.getString("role"),
                rs.getString("portrait_path"),
                rs.getString("public_description"),
                rs.getString("personality"),
                json.readStringList(rs.getString("quick_questions")),
                rs.getBoolean("is_witness"),
                rs.getBoolean("is_true_culprit"),
                rs.getInt("sort_order")
        );
    }

    private EvidenceDto mapEvidence(ResultSet rs, int rowNum) throws SQLException {
        return new EvidenceDto(
                rs.getString("id"),
                rs.getString("scene_id"),
                rs.getString("name"),
                rs.getString("asset_path"),
                rs.getString("short_description"),
                rs.getString("full_description"),
                rs.getString("importance"),
                rs.getBoolean("initial_unlocked"),
                json.readStringList(rs.getString("tags")),
                rs.getInt("sort_order")
        );
    }

    private TimelineEventDto mapTimeline(ResultSet rs, int rowNum) throws SQLException {
        return new TimelineEventDto(
                rs.getString("id"),
                rs.getString("time_label"),
                rs.getString("description"),
                json.readStringList(rs.getString("required_evidence_ids")),
                rs.getInt("sort_order")
        );
    }

    private DialogueTurnDto mapDialogueTurn(ResultSet rs, int rowNum) throws SQLException {
        return new DialogueTurnDto(
                rs.getObject("id", UUID.class),
                rs.getString("suspect_id"),
                rs.getString("question"),
                rs.getString("shown_evidence_id"),
                rs.getString("answer"),
                rs.getString("mood"),
                json.readStringList(rs.getString("unlocked_evidence_ids")),
                rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private NoteDto mapNote(ResultSet rs, int rowNum) throws SQLException {
        return new NoteDto(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getString("body"),
                rs.getString("source"),
                json.readStringList(rs.getString("related_evidence_ids")),
                json.readStringList(rs.getString("related_suspect_ids")),
                rs.getObject("created_at", OffsetDateTime.class)
        );
    }
}
