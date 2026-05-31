package com.morii.backend.game;

import com.morii.backend.game.DeepSeekClient.AiResult;
import com.morii.backend.game.DeepSeekClient.StreamResult;
import com.morii.backend.game.GameDtos.AnalyzeNotesResponse;
import com.morii.backend.game.GameDtos.CaseBundle;
import com.morii.backend.game.GameDtos.DialogueTurnDto;
import com.morii.backend.game.GameDtos.EvidenceDto;
import com.morii.backend.game.GameDtos.InspectEvidenceResponse;
import com.morii.backend.game.GameDtos.InterrogateResponse;
import com.morii.backend.game.GameDtos.NoteDto;
import com.morii.backend.game.GameDtos.SessionDto;
import com.morii.backend.game.GameDtos.StartSessionResponse;
import com.morii.backend.game.GameDtos.SuspectDto;
import com.morii.backend.game.GameDtos.VerdictResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class GameService {
    private static final Logger log = LoggerFactory.getLogger(GameService.class);

    private final GameRepository repository;
    private final DeepSeekClient aiClient;

    public GameService(GameRepository repository, DeepSeekClient aiClient) {
        this.repository = repository;
        this.aiClient = aiClient;
    }

    public CaseBundle getCaseBundle() {
        return repository.getCaseBundle();
    }

    public StartSessionResponse startSession(String playerName) {
        UUID sessionId = repository.createSession(playerName);
        repository.unlockInitialEvidence(sessionId);
        CaseBundle bundle = repository.getCaseBundle();
        return new StartSessionResponse(
                sessionId,
                bundle,
                repository.getUnlockedEvidenceIds(sessionId),
                bundle.caseInfo().openingText()
        );
    }

    public SessionDto getSession(UUID sessionId) {
        return repository.getSession(sessionId);
    }

    public InspectEvidenceResponse inspectEvidence(UUID sessionId, String evidenceId) {
        Set<String> unlocked = new LinkedHashSet<>();
        unlocked.add(evidenceId);
        List<String> current = repository.getUnlockedEvidenceIds(sessionId);

        if ("large_bottle".equals(evidenceId)) {
            unlocked.add("anonymous_letter");
        }
        if ("small_case".equals(evidenceId) && current.contains("bride_statement")) {
            unlocked.add("fingerprint");
        }
        if ("bridal".equals(repository.findEvidence(evidenceId).map(EvidenceDto::sceneId).orElse(""))) {
            unlocked.add("bride_statement");
        }

        List<String> ids = unlocked.stream().filter(id -> repository.findEvidence(id).isPresent()).toList();
        repository.unlockEvidence(sessionId, ids, "evidence", evidenceId);
        return new InspectEvidenceResponse(ids, evidenceName(evidenceId) + " 已加入线索板。");
    }

    public InspectEvidenceResponse inspectScene(UUID sessionId, String sceneId) {
        List<String> ids = repository.getCaseBundle().evidence().stream()
                .filter(e -> sceneId.equals(e.sceneId()))
                .filter(e -> !"capsule".equals(e.id()))
                .map(EvidenceDto::id)
                .limit(2)
                .toList();
        repository.unlockEvidence(sessionId, ids, "scene", sceneId);
        return new InspectEvidenceResponse(ids, "当前场景的关键线索已记录。");
    }

    public InterrogateResponse interrogate(UUID sessionId, String suspectId, String question, String shownEvidenceId) {
        SuspectDto suspect = repository.findSuspect(suspectId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown suspect: " + suspectId));
        List<String> before = repository.getUnlockedEvidenceIds(sessionId);
        List<String> ruleUnlocks = ruleUnlocks(suspectId, question, shownEvidenceId, before);
        repository.unlockEvidence(sessionId, ruleUnlocks, "interrogation", suspectId);
        List<String> after = repository.getUnlockedEvidenceIds(sessionId);

        String answer = callNpcAi(sessionId, suspect, question, shownEvidenceId, after, ruleUnlocks);
        String mood = moodFor(suspectId, question, shownEvidenceId, ruleUnlocks);
        if (shouldGuardConfession(suspectId, answer, after)) {
            answer = fallbackAnswer(suspectId, question, shownEvidenceId, ruleUnlocks);
            log.warn("AI answer replaced by guard: mode=normal sessionId={} suspectId={} reason=premature confession", sessionId, suspectId);
        }

        DialogueTurnDto turn = repository.insertDialogueTurn(
                sessionId,
                suspectId,
                question,
                shownEvidenceId == null || shownEvidenceId.isBlank() ? null : shownEvidenceId,
                answer,
                mood,
                ruleUnlocks,
                Map.of(
                        "unlockedEvidenceIds", after,
                        "shownEvidenceId", shownEvidenceId == null ? "" : shownEvidenceId,
                        "guarded", false
                ),
                aiClient.model()
        );
        return new InterrogateResponse(answer, mood, shownEvidenceName(shownEvidenceId), ruleUnlocks, turn);
    }

    public SseEmitter streamInterrogate(UUID sessionId, String suspectId, String question, String shownEvidenceId) {
        SseEmitter emitter = new SseEmitter(120_000L);
        CompletableFuture.runAsync(() -> {
            SuspectDto suspect = repository.findSuspect(suspectId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown suspect: " + suspectId));
            List<String> before = repository.getUnlockedEvidenceIds(sessionId);
            List<String> ruleUnlocks = ruleUnlocks(suspectId, question, shownEvidenceId, before);
            repository.unlockEvidence(sessionId, ruleUnlocks, "interrogation", suspectId);
            List<String> after = repository.getUnlockedEvidenceIds(sessionId);
            String mood = moodFor(suspectId, question, shownEvidenceId, ruleUnlocks);

            sendEvent(emitter, "meta", Map.of(
                    "suspectId", suspectId,
                    "suspectName", suspect.name(),
                    "mood", mood,
                    "shownEvidenceName", shownEvidenceName(shownEvidenceId),
                    "unlockedEvidenceIds", ruleUnlocks
            ));

            PromptPayload prompt = buildNpcPrompt(suspect, question, shownEvidenceId, after, ruleUnlocks);
            String answer;
            try {
                StreamResult result = aiClient.chatStream(prompt.system(), prompt.user(), delta -> sendEvent(emitter, "delta", Map.of("text", delta)));
                answer = result.content();
                repository.logAiCall(
                        sessionId,
                        "interrogate",
                        aiClient.model(),
                        result.request(),
                        Map.of("content", answer),
                        null,
                        result.latencyMs()
                );
                if (answer == null || answer.isBlank()) {
                    answer = fallbackAnswer(suspectId, question, shownEvidenceId, ruleUnlocks);
                    log.warn("AI fallback used: mode=stream sessionId={} suspectId={} reason=empty model response", sessionId, suspectId);
                    sendEvent(emitter, "delta", Map.of("text", answer));
                }
            } catch (Exception e) {
                answer = fallbackAnswer(suspectId, question, shownEvidenceId, ruleUnlocks);
                log.warn("AI fallback used: mode=stream sessionId={} suspectId={} reason={}", sessionId, suspectId, e.getMessage());
                repository.logAiCall(
                        sessionId,
                        "interrogate",
                        aiClient.model(),
                        Map.of("question", question),
                        Map.of(),
                        e.getMessage(),
                        null
                );
                sendEvent(emitter, "delta", Map.of("text", answer));
            }

            if (shouldGuardConfession(suspectId, answer, after)) {
                answer = fallbackAnswer(suspectId, question, shownEvidenceId, ruleUnlocks);
                log.warn("AI answer replaced by guard: mode=stream sessionId={} suspectId={} reason=premature confession", sessionId, suspectId);
                sendEvent(emitter, "replace", Map.of("text", answer));
            }

            DialogueTurnDto turn = repository.insertDialogueTurn(
                    sessionId,
                    suspectId,
                    question,
                    shownEvidenceId == null || shownEvidenceId.isBlank() ? null : shownEvidenceId,
                    answer,
                    mood,
                    ruleUnlocks,
                    prompt.snapshot(),
                    aiClient.model()
            );
            sendEvent(emitter, "done", Map.of(
                    "answer", answer,
                    "mood", mood,
                    "shownEvidenceName", shownEvidenceName(shownEvidenceId),
                    "unlockedEvidenceIds", ruleUnlocks,
                    "turn", turn
            ));
            emitter.complete();
        }).exceptionally(error -> {
            emitter.completeWithError(error);
            return null;
        });
        return emitter;
    }

    public AnalyzeNotesResponse analyzeNotes(UUID sessionId) {
        repository.deleteNotes(sessionId, "system");
        List<String> unlocked = repository.getUnlockedEvidenceIds(sessionId);
        List<NoteDto> notes = new ArrayList<>();

        if (unlocked.contains("large_bottle") && unlocked.contains("death_scene")) {
            notes.add(repository.insertNote(
                    sessionId,
                    "大药瓶不是服药来源",
                    "神林动的是家里的大药瓶，但案发现场记录显示死者当天从随身小药盒取药。",
                    "system",
                    List.of("large_bottle", "death_scene"),
                    List.of("kanbayashi")
            ));
        }
        if (unlocked.contains("empty_case") && unlocked.contains("bride_statement")) {
            notes.add(repository.insertNote(
                    sessionId,
                    "雪笹的提前投毒被时间线削弱",
                    "小药盒在 11:30 被新娘清空重装，因此婚礼前夜放入的胶囊不应继续留在盒内。",
                    "system",
                    List.of("empty_case", "bride_statement"),
                    List.of("yukizasa", "bride")
            ));
        }
        if (unlocked.contains("fingerprint") && unlocked.contains("suruga_sensitivity")) {
            notes.add(repository.insertNote(
                    sessionId,
                    "关键物从胶囊转向药盒",
                    "骏河一直否认往胶囊里加东西，但对药盒、指纹和调包异常敏感。",
                    "system",
                    List.of("fingerprint", "suruga_sensitivity", "small_case"),
                    List.of("suruga")
            ));
        }
        if (notes.isEmpty()) {
            notes.add(repository.insertNote(
                    sessionId,
                    "继续审问",
                    "目前证词还不足以排除“加胶囊”路径。建议追问小药盒装药时间、药盒指纹和大药瓶是否被实际使用。",
                    "system",
                    List.of(),
                    List.of()
            ));
        }
        return new AnalyzeNotesResponse(notes);
    }

    public VerdictResponse submitVerdict(UUID sessionId, String accused, String keyItem, String reason) {
        String ending = "FALSE_ACCUSATION";
        String score = "C";
        String title = "错误指控";
        String body = "你的推理被三名嫌疑人的自保证词带偏了。这个案件里，动机并不稀缺，真正稀缺的是生效路径。";

        if ("suruga".equals(accused) && "small_case".equals(keyItem)) {
            ending = "TRUE_ENDING";
            score = containsAny(reason, "指纹", "调包", "11:30", "药盒", "小药盒") ? "S" : "A";
            title = "真结局：药盒本身";
            body = "你指出真正的关键不是哪一颗胶囊，而是这个小药盒本身。神林和雪笹一脸茫然，只有骏河的表情在一瞬间裂开。";
        } else if ("suruga".equals(accused)) {
            ending = "RIGHT_KILLER_WEAK";
            score = "B";
            title = "凶手正确，手法不足";
            body = "你抓住了骏河，但仍把焦点放在胶囊上。若不能说明小药盒被整盒调包，结案陈词还不够锋利。";
        }

        repository.upsertVerdict(sessionId, accused, keyItem, reason, ending, score, title, body);
        return new VerdictResponse(ending, score, title, body);
    }

    private String callNpcAi(
            UUID sessionId,
            SuspectDto suspect,
            String question,
            String shownEvidenceId,
            List<String> unlockedEvidenceIds,
            List<String> ruleUnlocks
    ) {
        PromptPayload prompt = buildNpcPrompt(suspect, question, shownEvidenceId, unlockedEvidenceIds, ruleUnlocks);
        try {
            AiResult result = aiClient.chat(prompt.system(), prompt.user());
            repository.logAiCall(sessionId, "interrogate", aiClient.model(), result.request(), result.response(), null, result.latencyMs());
            if (result.content() != null && !result.content().isBlank()) {
                return result.content();
            }
            log.warn("AI fallback used: mode=normal sessionId={} suspectId={} reason=empty model response", sessionId, suspect.id());
        } catch (Exception e) {
            log.warn("AI fallback used: mode=normal sessionId={} suspectId={} reason={}", sessionId, suspect.id(), e.getMessage());
            repository.logAiCall(sessionId, "interrogate", aiClient.model(), Map.of("question", question), Map.of(), e.getMessage(), null);
        }
        return fallbackAnswer(suspect.id(), question, shownEvidenceId, ruleUnlocks);
    }

    private PromptPayload buildNpcPrompt(
            SuspectDto suspect,
            String question,
            String shownEvidenceId,
            List<String> unlockedEvidenceIds,
            List<String> ruleUnlocks
    ) {
        String system = repository.getSuspectSystemPrompt(suspect.id()) + """

                额外规则：
                - 当前回答只能基于角色已知信息，不得使用全知视角。
                - 如果证据不足，允许紧张、回避、反问，但不要主动说出完整手法。
                - 你可以承认局部事实，但不能替玩家完成最终推理。
                """;
        String user = """
                当前已解锁线索：%s
                当前出示证物：%s
                本轮规则允许透露的线索：%s
                玩家问题：%s
                请用角色口吻回答。
                """.formatted(
                evidenceNames(unlockedEvidenceIds),
                shownEvidenceName(shownEvidenceId),
                evidenceNames(ruleUnlocks),
                question
        );
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("unlockedEvidenceIds", unlockedEvidenceIds);
        snapshot.put("shownEvidenceId", shownEvidenceId == null ? "" : shownEvidenceId);
        snapshot.put("ruleUnlocks", ruleUnlocks);
        return new PromptPayload(system, user, snapshot);
    }

    private void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to send SSE event", e);
        }
    }

    private List<String> ruleUnlocks(String suspectId, String question, String evidenceId, List<String> current) {
        Set<String> unlocked = new LinkedHashSet<>();
        String q = question == null ? "" : question;

        if ("bride".equals(suspectId)) {
            unlocked.add("bride_statement");
            if (containsAny(q, "11", "十一", "装药", "空", "药盒", "包")) {
                unlocked.add("empty_case");
            }
        }
        if ("kanbayashi".equals(suspectId)) {
            if ("anonymous_letter".equals(evidenceId) || containsAny(q, "匿名", "信")) {
                unlocked.add("anonymous_letter");
            }
            if ("large_bottle".equals(evidenceId) || containsAny(q, "大药瓶", "书房", "药瓶", "胶囊")) {
                unlocked.add("large_bottle");
                unlocked.add("no_small_case_touch");
            }
        }
        if ("yukizasa".equals(suspectId)) {
            if (containsAny(q, "空", "装药", "上午", "11", "十一", "新娘")) {
                unlocked.add("empty_case");
                unlocked.add("bride_statement");
            }
            if ("purchase_record".equals(evidenceId) || containsAny(q, "旧情人", "购药", "毒胶囊", "偷")) {
                unlocked.add("purchase_record");
                unlocked.add("small_case");
            }
        }
        if ("suruga".equals(suspectId)) {
            if ("fingerprint".equals(evidenceId) || containsAny(q, "指纹", "调包", "药盒本身", "不是胶囊", "小药盒", "药盒")) {
                unlocked.add("small_case");
                unlocked.add("fingerprint");
                unlocked.add("suruga_sensitivity");
            }
        }
        if ("small_case".equals(evidenceId) && current.contains("bride_statement")) {
            unlocked.add("fingerprint");
        }
        return unlocked.stream().toList();
    }

    private String moodFor(String suspectId, String question, String evidenceId, List<String> unlocks) {
        if ("suruga".equals(suspectId) && (unlocks.contains("fingerprint") || containsAny(question, "调包", "药盒本身"))) {
            return "nervous";
        }
        if ("kanbayashi".equals(suspectId) && (unlocks.contains("anonymous_letter") || unlocks.contains("large_bottle"))) {
            return "cornered";
        }
        if ("yukizasa".equals(suspectId) && (unlocks.contains("empty_case") || unlocks.contains("purchase_record"))) {
            return "defensive";
        }
        if ("bride".equals(suspectId)) {
            return "anxious";
        }
        return "normal";
    }

    private boolean shouldGuardConfession(String suspectId, String answer, List<String> unlocked) {
        if (answer == null) {
            return false;
        }
        boolean confession = containsAny(answer, "我是凶手", "我杀了", "我调包", "我换掉了", "我毒死");
        if (!confession) {
            return false;
        }
        if (!"suruga".equals(suspectId)) {
            return true;
        }
        return !(unlocked.contains("fingerprint") && unlocked.contains("suruga_sensitivity") && unlocked.contains("bride_statement"));
    }

    private String fallbackAnswer(String suspectId, String question, String evidenceId, List<String> unlocks) {
        if ("bride".equals(suspectId)) {
            return "我上午十一点半才想起来装药。之前那只小药盒一直空着，我太慌了，就把里面清空后重新装了一版新的鼻炎药。";
        }
        if ("kanbayashi".equals(suspectId)) {
            if (unlocks.contains("large_bottle")) {
                return "我只进过书房，也只碰过家里的大药瓶。那是我自己的罪，和她包里的小药盒无关。";
            }
            return "我讨厌他，这一点我不否认。但婚礼那天我没有靠近新娘的包，更没有碰过她的小药盒。";
        }
        if ("yukizasa".equals(suspectId)) {
            if (unlocks.contains("empty_case")) {
                return "新娘向来忘事，上午那只小药盒还是空的。她总是把重要的小事拖到最后，这并不奇怪。";
            }
            return "死者背叛过很多人，我只是其中一个。你想问什么，请更具体一点。";
        }
        if ("suruga".equals(suspectId)) {
            if (unlocks.contains("fingerprint")) {
                return "我没有往任何胶囊或药瓶里加过东西。药盒每天被那么多人看见，留下不明指纹并不稀奇。";
            }
            return "我可以很明确地说，我没有往任何胶囊或药瓶里加过任何东西。这句话你可以原样记进笔录。";
        }
        return "我需要你问得更具体一点。";
    }

    private String shownEvidenceName(String evidenceId) {
        if (evidenceId == null || evidenceId.isBlank()) {
            return "未出示证物";
        }
        return evidenceName(evidenceId);
    }

    private String evidenceNames(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return "无";
        }
        return ids.stream().map(this::evidenceName).toList().toString();
    }

    private String evidenceName(String id) {
        return repository.findEvidence(id).map(EvidenceDto::name).orElse(id == null ? "" : id);
    }

    private boolean containsAny(String text, String... words) {
        if (text == null) {
            return false;
        }
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private record PromptPayload(String system, String user, Map<String, Object> snapshot) {
    }
}
