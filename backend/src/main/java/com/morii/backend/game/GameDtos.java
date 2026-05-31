package com.morii.backend.game;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class GameDtos {
    private GameDtos() {
    }

    public record CaseBundle(
            CaseInfo caseInfo,
            List<SceneDto> scenes,
            List<SuspectDto> suspects,
            List<EvidenceDto> evidence,
            List<TimelineEventDto> timeline
    ) {
    }

    public record CaseInfo(
            String id,
            String title,
            String subtitle,
            String description,
            String openingText,
            String truthSummary
    ) {
    }

    public record SceneDto(
            String id,
            String name,
            String assetPath,
            String description,
            int sortOrder
    ) {
    }

    public record SuspectDto(
            String id,
            String sceneId,
            String name,
            String role,
            String portraitPath,
            String publicDescription,
            String personality,
            List<String> quickQuestions,
            boolean witness,
            boolean trueCulprit,
            int sortOrder
    ) {
    }

    public record EvidenceDto(
            String id,
            String sceneId,
            String name,
            String assetPath,
            String shortDescription,
            String fullDescription,
            String importance,
            boolean initialUnlocked,
            List<String> tags,
            int sortOrder
    ) {
    }

    public record TimelineEventDto(
            String id,
            String timeLabel,
            String description,
            List<String> requiredEvidenceIds,
            int sortOrder
    ) {
    }

    public record DialogueTurnDto(
            UUID id,
            String suspectId,
            String question,
            String shownEvidenceId,
            String answer,
            String mood,
            List<String> unlockedEvidenceIds,
            OffsetDateTime createdAt
    ) {
    }

    public record NoteDto(
            UUID id,
            String title,
            String body,
            String source,
            List<String> relatedEvidenceIds,
            List<String> relatedSuspectIds,
            OffsetDateTime createdAt
    ) {
    }

    public record SessionDto(
            UUID sessionId,
            String caseId,
            String status,
            String currentScreen,
            List<String> unlockedEvidenceIds,
            List<DialogueTurnDto> dialogue,
            List<NoteDto> notes
    ) {
    }

    public record StartSessionRequest(String playerName) {
    }

    public record StartSessionResponse(
            UUID sessionId,
            CaseBundle caseBundle,
            List<String> unlockedEvidenceIds,
            String openingText
    ) {
    }

    public record InspectEvidenceRequest(
            UUID sessionId,
            String evidenceId
    ) {
    }

    public record InspectEvidenceResponse(
            List<String> unlockedEvidenceIds,
            String message
    ) {
    }

    public record InterrogateRequest(
            UUID sessionId,
            String suspectId,
            String question,
            String shownEvidenceId
    ) {
    }

    public record InterrogateResponse(
            String answer,
            String mood,
            String shownEvidenceName,
            List<String> unlockedEvidenceIds,
            DialogueTurnDto turn
    ) {
    }

    public record AnalyzeNotesRequest(UUID sessionId) {
    }

    public record AnalyzeNotesResponse(List<NoteDto> notes) {
    }

    public record VerdictRequest(
            UUID sessionId,
            String accused,
            String keyItem,
            String reason
    ) {
    }

    public record VerdictResponse(
            String ending,
            String score,
            String title,
            String body
    ) {
    }
}
