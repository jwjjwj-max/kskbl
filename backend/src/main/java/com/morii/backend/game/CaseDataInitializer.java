package com.morii.backend.game;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CaseDataInitializer implements ApplicationRunner {
    private static final String CASE_ID = GameRepository.CASE_ID;

    private final GameRepository repository;
    private final JdbcTemplate jdbc;
    private final JsonSupport json;

    public CaseDataInitializer(GameRepository repository, JdbcTemplate jdbc, JsonSupport json) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedCase();
        seedScenes();
        seedEvidence();
        seedSuspects();
        seedTimeline();
    }

    private void seedCase() {
        jdbc.update(
                """
                        INSERT INTO cases(id, title, subtitle, description, opening_text, truth_summary)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (id) DO UPDATE SET
                            title = EXCLUDED.title,
                            subtitle = EXCLUDED.subtitle,
                            description = EXCLUDED.description,
                            opening_text = EXCLUDED.opening_text,
                            truth_summary = EXCLUDED.truth_summary,
                            updated_at = NOW()
                        """,
                CASE_ID,
                "婚礼上的毒杀",
                "三个人都以为自己杀了新郎，但只有一只手真正生效。",
                "单人 AI 审问推理游戏。玩家需要通过搜证、出示证物和审问，找出真正生效的投毒路径。",
                "婚礼的香槟塔旁，新郎穗高诚倒下了。氰化物。在场宾客一百余人，但真正动过手的，只有三个人，而他们每一个，都以为自己赢了。",
                "真凶是经纪人骏河。关键不是哪一颗胶囊，而是被整盒调包的小药盒。"
        );
    }

    private void seedScenes() {
        upsertScene("wedding", "宴会厅", "./assets/scenes/wedding_hall.png", "新郎在婚礼现场从随身小药盒取药后倒下。", 10);
        upsertScene("evidence", "证物桌", "./assets/scenes/evidence_table.png", "六件证物共同构成药物、药盒和时间线。", 20);
        upsertScene("bridal", "新娘休息室", "./assets/scenes/bridal_room.png", "新娘在这里回忆 11:30 清空并重装小药盒。", 30);
        upsertScene("study", "死者书房", "./assets/scenes/study.png", "大药瓶所在处，神林的错误投毒路径。", 40);
        upsertScene("editor", "编辑办公室", "./assets/scenes/editor_office.png", "雪笹与旧情人购药记录的线索来源。", 50);
        upsertScene("backstage", "后台走廊", "./assets/scenes/backstage.png", "11:30 后短暂无人注意的调包机会。", 60);
        upsertScene("interrogation", "审问室", "./assets/scenes/interrogation.png", "审问所有嫌疑人和证人的通用空间。", 70);
        upsertScene("finale", "真结局对峙", "./assets/scenes/finale.png", "侦探指出药盒本身，骏河瞬间失态。", 80);
    }

    private void seedEvidence() {
        upsertEvidence("autopsy", "evidence", "验尸报告", null,
                "死因是氰化物，胃内残留为鼻炎药胶囊。",
                "报告确认死者服下的是伪装成鼻炎药胶囊的氰化物。它解释了死因，却不能说明是哪一次投毒真正生效。",
                "important", true, List.of("poison", "capsule"), 10);
        upsertEvidence("death_scene", "wedding", "案发现场记录", null,
                "死者从随身小药盒中取药后倒下。",
                "目击者确认，死者临时服药时没有去家中大药瓶，而是从新娘包里的小药盒取用。",
                "normal", true, List.of("scene", "small_case"), 20);
        upsertEvidence("large_bottle", "study", "大药瓶", null,
                "家中药瓶检出一颗毒胶囊。",
                "大药瓶里的毒胶囊能解释神林的行为，但死者当天并未从大药瓶取药。",
                "important", false, List.of("kanbayashi", "capsule"), 30);
        upsertEvidence("anonymous_letter", "study", "匿名信", null,
                "婚礼前夜寄给神林，附有一颗毒胶囊。",
                "匿名信把神林推向犯罪，但也把他的手法限定在家里的大药瓶。",
                "normal", false, List.of("kanbayashi", "letter"), 40);
        upsertEvidence("purchase_record", "editor", "旧情人购药记录", null,
                "旧情人在自杀前制成 12 颗毒胶囊。",
                "记录证明毒胶囊来源，也解释了雪笹为何能取得其中一颗。",
                "normal", false, List.of("yukizasa", "capsule"), 50);
        upsertEvidence("bride_statement", "bridal", "新娘证词卡", null,
                "新娘 11:30 才清空并重装小药盒。",
                "这条证词会排除雪笹提前投进小药盒的胶囊，因为那颗胶囊在重装时已经被清掉。",
                "decisive", false, List.of("bride", "time"), 60);
        upsertEvidence("small_case", "evidence", "小药盒", null,
                "现场回收，内有毒胶囊。",
                "真正决定案件的不是胶囊，而是这只药盒是否仍是新娘 11:30 刚装好的那只。",
                "decisive", false, List.of("small_case", "suruga"), 70);
        upsertEvidence("fingerprint", "evidence", "药盒指纹", null,
                "回收药盒上的指纹不属于新娘。",
                "如果小药盒指纹不属于新娘，它就可能不是她当天装药的那只，而是被整盒调包。",
                "decisive", false, List.of("small_case", "fingerprint", "suruga"), 80);
        upsertEvidence("empty_case", "bridal", "小药盒空置记录", null,
                "婚礼当天上午，小药盒曾一直是空的。",
                "雪笹以为这是自己的伪装，实际上它证明她提前放入的毒胶囊已经被清空。",
                "important", false, List.of("bride", "yukizasa", "time"), 90);
        upsertEvidence("no_small_case_touch", "study", "神林未碰小药盒", null,
                "神林坚称自己只接触过大药瓶。",
                "这句自保反而帮助排除他对小药盒的直接调包可能。",
                "normal", false, List.of("kanbayashi", "small_case"), 100);
        upsertEvidence("suruga_sensitivity", "backstage", "骏河的药盒敏感", null,
                "骏河对“药盒本身”的反应异常。",
                "他不断否认往胶囊里加东西，但始终绕开是否接触过药盒本身。",
                "decisive", false, List.of("suruga", "small_case"), 110);
        upsertEvidence("capsule", "evidence", "胶囊", null,
                "伪装成鼻炎药的毒胶囊。",
                "三名嫌疑人都围绕胶囊行动，但最终真正生效的路径不是单颗胶囊，而是整盒调包。",
                "important", false, List.of("capsule"), 120);
    }

    private void seedSuspects() {
        upsertSuspect(
                "kanbayashi",
                "study",
                "神林贵弘",
                "新娘的哥哥",
                "./assets/characters/kanbayashi.png",
                "他厌恶死者，也在婚礼前夜去过死者书房；匿名信和大药瓶会让他明显动摇。",
                "表面礼貌克制，内心压抑着嫉妒与不安，说话常带叹气或停顿。",
                CasePrompts.KANBAYASHI,
                List.of("你婚礼前夜去过书房吗？", "匿名信是谁给你的？", "你碰过小药盒吗？"),
                false,
                false,
                10
        );
        upsertSuspect(
                "yukizasa",
                "editor",
                "雪笹香织",
                "出版社编辑，死者旧情人",
                "./assets/characters/yukizasa.png",
                "她与死者有长期地下关系，知道浪冈准子自杀和毒胶囊的来源。",
                "冷静、克制、善于用反问和逻辑转移话题。",
                CasePrompts.YUKIZASA,
                List.of("你见过新娘的小药盒吗？", "婚礼当天上午小药盒里有药吗？", "你知道旧情人的购药记录吗？"),
                false,
                false,
                20
        );
        upsertSuspect(
                "suruga",
                "backstage",
                "骏河直之",
                "死者经纪人",
                "./assets/characters/suruga.png",
                "他处理过死者许多私事，对浪冈准子的死亡反应异常强烈。",
                "最镇定、最自信，前期轻松甚至略带调侃，触及药盒核心会变得阴沉。",
                CasePrompts.SURUGA,
                List.of("你有没有往胶囊里加东西？", "你接触过小药盒本身吗？", "关键不是胶囊而是药盒，对吗？"),
                false,
                true,
                30
        );
        upsertSuspect(
                "bride",
                "bridal",
                "新娘",
                "证人",
                "./assets/characters/bride.png",
                "她不是嫌疑人，但掌握小药盒何时被清空重装。",
                "温柔、敏感、容易自责；悲伤慌乱，但被温和追问时能回忆出关键细节。",
                CasePrompts.BRIDE,
                List.of("你什么时候装的小药盒？", "小药盒之前是空的吗？", "药盒平时放在哪里？"),
                true,
                false,
                40
        );
    }

    private void seedTimeline() {
        upsertTimeline("t1", "婚礼前夜", "旧情人自杀，留下 12 颗伪装成鼻炎药的毒胶囊。", List.of("purchase_record"), 10);
        upsertTimeline("t2", "前夜", "神林收到匿名信，将一颗毒胶囊投入家中大药瓶。", List.of("anonymous_letter", "large_bottle"), 20);
        upsertTimeline("t3", "前夜", "雪笹提前把一颗毒胶囊放入新娘包里的小药盒。", List.of("purchase_record", "small_case"), 30);
        upsertTimeline("t4", "当天 11:30", "新娘清空并重装小药盒，之前的小药全部被替换。", List.of("bride_statement", "empty_case"), 40);
        upsertTimeline("t5", "11:30 之后", "骏河趁机整盒调包，换入预先装好毒胶囊的同款药盒。", List.of("fingerprint", "suruga_sensitivity"), 50);
        upsertTimeline("t6", "婚礼中", "死者从随身小药盒取药服下，当场中毒身亡。", List.of("death_scene"), 60);
    }

    private void upsertScene(String id, String name, String assetPath, String description, int sortOrder) {
        jdbc.update(
                """
                        INSERT INTO case_scenes(id, case_id, name, asset_path, description, sort_order)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (id) DO UPDATE SET
                            name = EXCLUDED.name,
                            asset_path = EXCLUDED.asset_path,
                            description = EXCLUDED.description,
                            sort_order = EXCLUDED.sort_order
                        """,
                id, CASE_ID, name, assetPath, description, sortOrder
        );
    }

    private void upsertEvidence(
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
        jdbc.update(
                """
                        INSERT INTO case_evidence(
                            id, case_id, scene_id, name, asset_path, short_description, full_description,
                            importance, initial_unlocked, tags, sort_order
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                        ON CONFLICT (id) DO UPDATE SET
                            scene_id = EXCLUDED.scene_id,
                            name = EXCLUDED.name,
                            asset_path = EXCLUDED.asset_path,
                            short_description = EXCLUDED.short_description,
                            full_description = EXCLUDED.full_description,
                            importance = EXCLUDED.importance,
                            initial_unlocked = EXCLUDED.initial_unlocked,
                            tags = EXCLUDED.tags,
                            sort_order = EXCLUDED.sort_order
                        """,
                id, CASE_ID, sceneId, name, assetPath, shortDescription, fullDescription,
                importance, initialUnlocked, json.write(tags), sortOrder
        );
    }

    private void upsertSuspect(
            String id,
            String sceneId,
            String name,
            String role,
            String portraitPath,
            String publicDescription,
            String personality,
            String systemPrompt,
            List<String> quickQuestions,
            boolean witness,
            boolean trueCulprit,
            int sortOrder
    ) {
        jdbc.update(
                """
                        INSERT INTO case_suspects(
                            id, case_id, scene_id, name, role, portrait_path, public_description,
                            personality, hidden_truth, system_prompt, quick_questions, is_witness,
                            is_true_culprit, sort_order
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                        ON CONFLICT (id) DO UPDATE SET
                            scene_id = EXCLUDED.scene_id,
                            name = EXCLUDED.name,
                            role = EXCLUDED.role,
                            portrait_path = EXCLUDED.portrait_path,
                            public_description = EXCLUDED.public_description,
                            personality = EXCLUDED.personality,
                            hidden_truth = EXCLUDED.hidden_truth,
                            system_prompt = EXCLUDED.system_prompt,
                            quick_questions = EXCLUDED.quick_questions,
                            is_witness = EXCLUDED.is_witness,
                            is_true_culprit = EXCLUDED.is_true_culprit,
                            sort_order = EXCLUDED.sort_order
                        """,
                id, CASE_ID, sceneId, name, role, portraitPath, publicDescription,
                personality, systemPrompt, systemPrompt, json.write(quickQuestions), witness,
                trueCulprit, sortOrder
        );
    }

    private void upsertTimeline(String id, String timeLabel, String description, List<String> requiredEvidenceIds, int sortOrder) {
        jdbc.update(
                """
                        INSERT INTO case_timeline_events(id, case_id, time_label, description, required_evidence_ids, sort_order)
                        VALUES (?, ?, ?, ?, ?::jsonb, ?)
                        ON CONFLICT (id) DO UPDATE SET
                            time_label = EXCLUDED.time_label,
                            description = EXCLUDED.description,
                            required_evidence_ids = EXCLUDED.required_evidence_ids,
                            sort_order = EXCLUDED.sort_order
                        """,
                id, CASE_ID, timeLabel, description, json.write(requiredEvidenceIds), sortOrder
        );
    }
}
