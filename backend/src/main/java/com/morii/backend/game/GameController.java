package com.morii.backend.game;

import com.morii.backend.game.GameDtos.AnalyzeNotesRequest;
import com.morii.backend.game.GameDtos.AnalyzeNotesResponse;
import com.morii.backend.game.GameDtos.CaseBundle;
import com.morii.backend.game.GameDtos.InspectEvidenceRequest;
import com.morii.backend.game.GameDtos.InspectEvidenceResponse;
import com.morii.backend.game.GameDtos.InterrogateRequest;
import com.morii.backend.game.GameDtos.InterrogateResponse;
import com.morii.backend.game.GameDtos.SessionDto;
import com.morii.backend.game.GameDtos.StartSessionRequest;
import com.morii.backend.game.GameDtos.StartSessionResponse;
import com.morii.backend.game.GameDtos.VerdictRequest;
import com.morii.backend.game.GameDtos.VerdictResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@RestController
@RequestMapping
public class GameController {
    private static final Logger log = LoggerFactory.getLogger(GameController.class);

    private final GameService service;

    public GameController(GameService service) {
        this.service = service;
    }

    @GetMapping("/case/wedding-poisoning")
    public CaseBundle getCase() {
        log.info("API call: get case bundle");
        return service.getCaseBundle();
    }

    @PostMapping("/session/start")
    public StartSessionResponse startSession(@RequestBody(required = false) StartSessionRequest request) {
        log.info("API call: start session player={}", request == null ? "" : request.playerName());
        return service.startSession(request == null ? null : request.playerName());
    }

    @GetMapping("/session/{id}")
    public SessionDto getSession(@PathVariable UUID id) {
        log.info("API call: get session sessionId={}", id);
        return service.getSession(id);
    }

    @PostMapping("/evidence/inspect")
    public InspectEvidenceResponse inspectEvidence(@RequestBody InspectEvidenceRequest request) {
        log.info("API call: inspect evidence sessionId={} evidenceId={}", request.sessionId(), request.evidenceId());
        return service.inspectEvidence(request.sessionId(), request.evidenceId());
    }

    @PostMapping("/scene/{sceneId}/inspect")
    public InspectEvidenceResponse inspectScene(@PathVariable String sceneId, @RequestBody InspectEvidenceRequest request) {
        log.info("API call: inspect scene sessionId={} sceneId={}", request.sessionId(), sceneId);
        return service.inspectScene(request.sessionId(), sceneId);
    }

    @PostMapping("/interrogate")
    public InterrogateResponse interrogate(@RequestBody InterrogateRequest request) {
        log.info(
                "API call: interrogate sessionId={} suspectId={} shownEvidenceId={} question={}",
                request.sessionId(),
                request.suspectId(),
                request.shownEvidenceId(),
                preview(request.question())
        );
        return service.interrogate(request.sessionId(), request.suspectId(), request.question(), request.shownEvidenceId());
    }

    @PostMapping(value = "/interrogate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamInterrogate(@RequestBody InterrogateRequest request) {
        log.info(
                "API call: stream interrogate sessionId={} suspectId={} shownEvidenceId={} question={}",
                request.sessionId(),
                request.suspectId(),
                request.shownEvidenceId(),
                preview(request.question())
        );
        return service.streamInterrogate(request.sessionId(), request.suspectId(), request.question(), request.shownEvidenceId());
    }

    @PostMapping("/notes/analyze")
    public AnalyzeNotesResponse analyzeNotes(@RequestBody AnalyzeNotesRequest request) {
        log.info("API call: analyze notes sessionId={}", request.sessionId());
        return service.analyzeNotes(request.sessionId());
    }

    @PostMapping("/verdict")
    public VerdictResponse verdict(@RequestBody VerdictRequest request) {
        log.info(
                "API call: submit verdict sessionId={} accused={} keyItem={} reason={}",
                request.sessionId(),
                request.accused(),
                request.keyItem(),
                preview(request.reason())
        );
        return service.submitVerdict(request.sessionId(), request.accused(), request.keyItem(), request.reason());
    }

    private String preview(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").strip();
        return normalized.length() <= 40 ? normalized : normalized.substring(0, 40) + "...";
    }
}
