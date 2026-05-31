const API_BASE = window.WENYING_API_BASE
  || (window.location.hostname === "localhost" || window.location.hostname === "127.0.0.1"
    ? "http://localhost:8081/api"
    : `${window.location.protocol}//${window.location.hostname}:8081/api`);
const POSTER_PATH = "./assets/poster/wenying-theater-poster.png";

const screens = [
  { id: "opening", label: "开场", minEvidence: 0 },
  { id: "evidence", label: "搜证", minEvidence: 1 },
  { id: "interrogate", label: "审问", minEvidence: 2 },
  { id: "board", label: "推理板", minEvidence: 4 },
  { id: "verdict", label: "结案", minEvidence: 6 }
];

const state = {
  loading: false,
  error: "",
  screen: "home",
  caseBundle: null,
  sessionId: null,
  sceneId: "",
  selectedSuspectId: "",
  selectedEvidenceId: "",
  evidenceToShow: "",
  unlocked: new Set(),
  dialogue: [],
  notes: [],
  verdict: null,
  toast: "",
  busy: false
};

async function api(path, options = {}) {
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(options.headers || {})
    }
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `HTTP ${response.status}`);
  }
  return response.json();
}

async function streamApi(path, body, handlers = {}) {
  const response = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });
  if (!response.ok || !response.body) {
    const text = await response.text();
    throw new Error(text || `HTTP ${response.status}`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";

  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    const blocks = buffer.split(/\r?\n\r?\n/);
    buffer = blocks.pop() || "";
    blocks.forEach((block) => handleSseBlock(block, handlers));
  }

  if (buffer.trim()) {
    handleSseBlock(buffer, handlers);
  }
}

function handleSseBlock(block, handlers) {
  let eventName = "message";
  const dataLines = [];
  block.split(/\r?\n/).forEach((line) => {
    if (line.startsWith("event:")) {
      eventName = line.slice(6).trim();
    }
    if (line.startsWith("data:")) {
      dataLines.push(line.slice(5).trimStart());
    }
  });

  if (!dataLines.length) return;
  const rawData = dataLines.join("\n");
  const payload = rawData ? JSON.parse(rawData) : null;
  handlers[eventName]?.(payload);
}

async function startSession() {
  state.loading = true;
  state.error = "";
  state.screen = "opening";
  render();
  try {
    const data = await api("/session/start", {
      method: "POST",
      body: JSON.stringify({ playerName: "侦探" })
    });
    state.caseBundle = data.caseBundle;
    state.sessionId = data.sessionId;
    state.unlocked = new Set(data.unlockedEvidenceIds || []);
    state.sceneId = firstScene()?.id || "";
    state.selectedEvidenceId = firstEvidence()?.id || "";
    state.selectedSuspectId = firstSuspect()?.id || "";
    state.dialogue = [];
    state.notes = [];
    state.verdict = null;
    state.screen = "opening";
  } catch (error) {
    state.error = `后端连接失败。请确认 Spring Boot 已启动在 ${API_BASE}。`;
  } finally {
    state.loading = false;
    render();
  }
}

function goHome() {
  state.loading = false;
  state.error = "";
  state.screen = "home";
  render();
}

function firstScene() {
  return state.caseBundle?.scenes?.[0];
}

function firstEvidence() {
  return state.caseBundle?.evidence?.[0];
}

function firstSuspect() {
  return state.caseBundle?.suspects?.[0];
}

function scenes() {
  return state.caseBundle?.scenes || [];
}

function suspects() {
  return state.caseBundle?.suspects || [];
}

function evidenceList() {
  return state.caseBundle?.evidence || [];
}

function timeline() {
  return state.caseBundle?.timeline || [];
}

function sceneById(id) {
  return scenes().find((item) => item.id === id) || firstScene();
}

function suspectById(id) {
  return suspects().find((item) => item.id === id) || firstSuspect();
}

function evidenceById(id) {
  return evidenceList().find((item) => item.id === id) || firstEvidence();
}

function has(id) {
  return state.unlocked.has(id);
}

function mergeUnlocked(ids = []) {
  const gained = [];
  ids.forEach((id) => {
    if (id && !state.unlocked.has(id)) {
      state.unlocked.add(id);
      const item = evidenceById(id);
      gained.push(item?.name || id);
    }
  });
  if (gained.length) {
    showToast(`解锁线索：${gained.join("、")}`);
  }
}

function showToast(message) {
  state.toast = message;
  render();
  window.clearTimeout(showToast.timer);
  showToast.timer = window.setTimeout(() => {
    state.toast = "";
    render();
  }, 2600);
}

function setScreen(screen) {
  const target = screens.find((item) => item.id === screen);
  if (target && state.unlocked.size < target.minEvidence) {
    showToast("先收集更多线索，再进入这个阶段。");
    return;
  }
  state.screen = screen;
  render();
}

function screenTemplate(content) {
  if (state.loading) {
    return `
      <div class="app-shell">
        <main class="screen">
          <section class="hero">
            <div class="hero-content">
              <p class="eyebrow">Loading</p>
              <h1>正在建立案件会话</h1>
              <p class="lede">前端正在从后端读取案件、人物、证物和时间线。</p>
            </div>
          </section>
        </main>
      </div>
    `;
  }
  if (state.error) {
    return `
      <div class="app-shell">
        <main class="screen">
          <section class="hero">
            <div class="hero-content">
              <p class="eyebrow">Backend Required</p>
              <h1>后端还没有连上</h1>
              <p class="lede">${state.error}</p>
              <div class="action-row">
                <button class="primary-button" data-action="retry" type="button">重新连接</button>
                <button class="plain-button" data-action="home" type="button">回到首页</button>
              </div>
            </div>
          </section>
        </main>
      </div>
    `;
  }

  const progress = Math.min(100, Math.round((state.unlocked.size / Math.max(evidenceList().length, 1)) * 100));
  return `
    <div class="app-shell">
      <header class="topbar">
        <div class="brand">
          <button class="brand-mark brand-home-button" data-action="home" type="button">问</button>
          <div>
            <b>${state.caseBundle.caseInfo.title}</b>
            <span>真实后端接口 · DeepSeek NPC</span>
          </div>
        </div>
        <nav class="step-nav">
          ${screens
            .map((item) => {
              const locked = state.unlocked.size < item.minEvidence;
              return `<button class="step-button ${state.screen === item.id ? "active" : ""} ${locked ? "locked" : ""}" data-screen="${item.id}" type="button">${item.label}</button>`;
            })
            .join("")}
        </nav>
        <div class="case-progress">
          <span>线索进度 ${state.unlocked.size}/${evidenceList().length}</span>
          <div class="progress-track"><div class="progress-fill" style="width:${progress}%"></div></div>
        </div>
      </header>
      <main class="screen">${content}</main>
      ${state.toast ? `<div class="toast">${state.toast}</div>` : ""}
    </div>
  `;
}

function render() {
  const app = document.querySelector("#app");
  if (state.screen === "home") {
    app.innerHTML = renderHome();
    bindEvents();
    return;
  }
  const view = state.loading || state.error
    ? ""
    : {
        opening: renderOpening,
        evidence: renderEvidence,
        interrogate: renderInterrogate,
        board: renderBoard,
        verdict: renderVerdict,
        ending: renderEnding
      }[state.screen]();
  app.innerHTML = screenTemplate(view);
  bindEvents();
}

function renderHome() {
  return `
    <div class="site-shell">
      <header class="site-nav">
        <button class="site-brand" data-action="home" type="button">
          <span class="site-brand-mark">问</span>
          <span><b>问影剧场</b><small>AI 审讯推理游戏生成器</small></span>
        </button>
        <nav class="site-links">
          <a href="#repertoire">剧目</a>
          <a href="#rules">玩法</a>
          <button class="primary-button" data-action="start-demo" type="button">进入 Demo</button>
        </nav>
      </header>

      <main class="site-home">
        <section class="theater-hero">
          <div class="theater-copy">
            <h1>问影剧场</h1>
            <p class="theater-line">沉浸式 AI 推理体验</p>
            <p class="theater-lede">三重视角、证词交锋、真相只有一个。你面对的不是选择题，而是一间会回应、会闪躲、会露出破绽的审讯室。</p>
            <div class="theater-actions">
              <button class="primary-button" data-action="start-demo" type="button">进入《婚礼上的毒杀》</button>
              <a class="plain-button" href="#repertoire">查看当前剧目</a>
            </div>
            <div class="theater-signature">
              <span>AI 审讯</span>
              <span>证词追问</span>
              <span>关键物推理</span>
            </div>
          </div>
          <figure class="poster-frame">
            <img src="${POSTER_PATH}" alt="问影剧场海报" />
          </figure>
        </section>

        <section class="repertoire-section" id="repertoire">
          <div class="section-heading">
            <h2>当前开放剧本</h2>
            <p>Demo 阶段先开放一个完整案件，后续剧本会沿用同一套审讯与推理框架。</p>
          </div>
          <article class="case-entry">
            <div class="case-entry-media">
              <img src="./assets/scenes/wedding_hall.png" alt="婚礼大厅" />
            </div>
            <div class="case-entry-copy">
              <span class="case-status">Demo 可体验</span>
              <h3>婚礼上的毒杀</h3>
              <p>婚礼现场，新郎在众目睽睽下倒下。三名嫌疑人都有动机，但真正的关键不在那颗胶囊，而在一个被调包的盒子。</p>
              <div class="case-entry-grid">
                <div><b>三名嫌疑人</b><span>每个人都藏着一段完整动机。</span></div>
                <div><b>四个可审问角色</b><span>AI 会根据证据状态回应。</span></div>
                <div><b>一个真相</b><span>指认凶手，也要说出关键物。</span></div>
              </div>
              <button class="primary-button" data-action="start-demo" type="button">开始调查</button>
            </div>
          </article>
        </section>

        <section class="rules-section" id="rules">
          <div class="rule-panel">
            <b>三重视角</b>
            <p>嫌疑人都以为自己已经完成了杀人，玩家需要从互相矛盾的视角里筛出真正路径。</p>
          </div>
          <div class="rule-panel">
            <b>证词交锋</b>
            <p>审讯、追问、出示证物，让谎言互相矛盾。</p>
          </div>
          <div class="rule-panel">
            <b>你的判断</b>
            <p>最后不是选一个名字，而是把凶手、关键物品和推理链一起钉死。</p>
          </div>
        </section>
      </main>
    </div>
  `;
}

function renderOpening() {
  const openingScene = sceneById("wedding") || firstScene();
  const info = state.caseBundle.caseInfo;
  return `
    <section class="hero">
      <img class="hero-image" src="${openingScene.assetPath}" alt="${openingScene.name}" />
      <div class="hero-content">
        <p class="eyebrow">AI Interrogation Mystery</p>
        <h1>${info.title}</h1>
        <p class="lede">${info.openingText}</p>
        <div class="opening-points">
          <div><b>搜证</b><span>从物证拼出药物、药盒和时间线。</span></div>
          <div><b>审问</b><span>向嫌疑人和证人追问，并出示证据施压。</span></div>
          <div><b>结案</b><span>指认真凶，同时说出真正的关键物品。</span></div>
        </div>
        <button class="primary-button" data-screen="evidence" type="button">开始调查</button>
      </div>
    </section>
  `;
}

function renderEvidence() {
  const scene = sceneById(state.sceneId);
  const selected = evidenceById(state.selectedEvidenceId);
  return `
    <section class="two-column">
      <div class="scene-stage">
        <img class="scene-image" src="${scene.assetPath}" alt="${scene.name}" />
        <div class="scene-tabs">
          ${scenes()
            .filter((item) => item.id !== "interrogation" && item.id !== "finale")
            .map((item) => `<button class="scene-tab ${state.sceneId === item.id ? "active" : ""}" data-scene="${item.id}" type="button">${item.name}</button>`)
            .join("")}
        </div>
        <div class="scene-copy">
          <p class="eyebrow">Scene</p>
          <h2>${scene.name}</h2>
          <p>${scene.description}</p>
          <button class="primary-button" data-action="inspect-scene" type="button">搜查当前场景</button>
        </div>
      </div>
      <aside class="side-panel">
        <div class="panel-head">
          <div>
            <h2>证物栏</h2>
            <p>点击证物查看详情。部分深入信息需要审问后解锁。</p>
          </div>
        </div>
        <div class="evidence-grid">
          ${evidenceList().map(renderEvidenceCard).join("")}
        </div>
        <div class="detail-panel">
          <span class="tag ${selected.importance}">${importanceText(selected.importance)}</span>
          <h3>${selected.name}</h3>
          <p>${has(selected.id) ? selected.fullDescription : selected.shortDescription}</p>
          <div class="action-row">
            <button class="plain-button" data-inspect="${selected.id}" type="button">检查证物</button>
            <button class="quiet-button" data-use-evidence="${selected.id}" type="button">带去审问</button>
          </div>
        </div>
      </aside>
    </section>
  `;
}

function renderEvidenceCard(item) {
  const locked = !has(item.id);
  return `
    <button class="evidence-card ${state.selectedEvidenceId === item.id ? "selected" : ""} ${locked ? "locked" : ""}" data-evidence="${item.id}" type="button">
      <strong>${item.name}</strong>
      <p>${locked ? item.shortDescription : item.fullDescription}</p>
      <div class="evidence-meta">
        <span class="tag ${item.importance}">${importanceText(item.importance)}</span>
        <span class="tag">${locked ? "未深入" : "已解锁"}</span>
      </div>
    </button>
  `;
}

function renderInterrogate() {
  const suspect = suspectById(state.selectedSuspectId);
  const availableEvidence = evidenceList().filter((item) => has(item.id));
  const bg = sceneById("interrogation");
  return `
    <section class="three-column">
      <aside class="side-panel">
        <div class="panel-head">
          <div>
            <h2>人物</h2>
            <p>每个人知道自己的秘密，但不知道别人也动过手。</p>
          </div>
        </div>
        <div class="suspect-list">
          ${suspects()
            .map(
              (item) => `
                <button class="suspect-card ${item.id === suspect.id ? "active" : ""}" data-suspect="${item.id}" type="button">
                  <div class="portrait-thumb"><img src="${item.portraitPath}" alt="${item.name}" /></div>
                  <div><strong>${item.name}</strong><p>${item.role}</p></div>
                </button>
              `
            )
            .join("")}
        </div>
      </aside>

      <section class="interview-main">
        <img class="interview-bg" src="${bg.assetPath}" alt="${bg.name}" />
        <div class="interview-content">
          <div class="character-header">
            <div class="character-portrait"><img src="${suspect.portraitPath}" alt="${suspect.name}" /></div>
            <div class="character-copy">
              <p class="eyebrow">${suspect.role}</p>
              <h2>${suspect.name}</h2>
              <p>${suspect.publicDescription}</p>
              <span class="tag">${suspect.personality}</span>
            </div>
          </div>

          <div class="chat-log">
            ${state.dialogue.length ? state.dialogue.map(renderDialogueRow).join("") : `<div class="dialogue-row system"><b>系统</b><p>选择人物，输入问题，也可以先选一件证物出示给对方。</p></div>`}
          </div>

          <div class="question-box">
            <form class="question-form" data-form="question">
              <input name="question" autocomplete="off" placeholder="输入你的审问问题..." />
              <button class="primary-button" type="submit" ${state.busy ? "disabled" : ""}>提问</button>
            </form>
            <div class="evidence-toolbar">
              <button class="evidence-token ${!state.evidenceToShow ? "active" : ""}" data-show-evidence="" type="button">不出示证物</button>
              ${availableEvidence
                .map((item) => `<button class="evidence-token ${state.evidenceToShow === item.id ? "active" : ""}" data-show-evidence="${item.id}" type="button">${item.name}</button>`)
                .join("")}
            </div>
            <div class="suggestions">
              ${(suspect.quickQuestions || []).map((q) => `<button class="suggest-button" data-suggest="${q}" type="button">${q}</button>`).join("")}
            </div>
          </div>
        </div>
      </section>

      <aside class="side-panel">
        <div class="panel-head">
          <div>
            <h2>当前线索</h2>
            <p>审问时可出示这些证据。</p>
          </div>
        </div>
        <div class="evidence-grid">
          ${availableEvidence.map(renderEvidenceCard).join("")}
        </div>
      </aside>
    </section>
  `;
}

function renderDialogueRow(item) {
  return `
    <div class="dialogue-row ${item.type}">
      <b>${item.speaker}</b>
      <p>${item.text}</p>
    </div>
  `;
}

function renderBoard() {
  const generatedNotes = state.notes.length
    ? state.notes
    : [{ title: "尚未整理", body: "点击“整理矛盾”后，后端会根据已解锁线索生成推理提示。" }];
  return `
    <section class="board-grid">
      <div class="board-panel">
        <div class="panel-head">
          <div>
            <h2>时间线</h2>
            <p>被锁住的节点代表证据链还不完整。</p>
          </div>
          <button class="primary-button" data-action="analyze" type="button">整理矛盾</button>
        </div>
        <div class="timeline">
          ${timeline()
            .map((item) => {
              const unlocked = (item.requiredEvidenceIds || []).every((id) => has(id));
              return `<div class="timeline-item ${unlocked ? "" : "locked"}"><b>${item.timeLabel}</b><p>${unlocked ? item.description : "线索不足，继续审问或搜证。"}</p></div>`;
            })
            .join("")}
        </div>
      </div>
      <div class="board-panel">
        <div class="panel-head">
          <div>
            <h2>矛盾笔记</h2>
            <p>这里由后端根据当前线索状态生成。</p>
          </div>
        </div>
        ${generatedNotes.map((note) => `<div class="note-card"><b>${note.title}</b><p>${note.body}</p></div>`).join("")}
        <div class="motive-grid">
          ${suspects()
            .filter((item) => !item.witness)
            .map((item) => `<article class="motive-card"><img src="${item.portraitPath}" alt="${item.name}" /><div><b>${item.name}</b><p>${item.publicDescription}</p></div></article>`)
            .join("")}
        </div>
      </div>
    </section>
  `;
}

function renderVerdict() {
  const finale = sceneById("finale");
  const keyItems = evidenceList().filter((item) => ["capsule", "large_bottle", "small_case"].includes(item.id));
  return `
    <section class="verdict-layout">
      <div class="scene-stage">
        <img class="scene-image" src="${finale.assetPath}" alt="${finale.name}" />
        <div class="scene-copy">
          <p class="eyebrow">Final Statement</p>
          <h2>结案陈词</h2>
          <p>你必须同时指出真正生效的人，以及真正关键的物品。只抓住动机还不够。</p>
        </div>
      </div>
      <aside class="verdict-panel">
        <h2>提交指控</h2>
        <p class="lede">后端会根据凶手、关键物品和理由判定结局。</p>
        <div class="field-grid">
          <div class="field-panel">
            <label for="accused">指认对象</label>
            <select id="accused">
              ${suspects().filter((item) => !item.witness).map((item) => `<option value="${item.id}">${item.name}，${item.role}</option>`).join("")}
            </select>
          </div>
          <div class="field-panel">
            <label for="keyItem">关键物品</label>
            <select id="keyItem">
              ${keyItems.map((item) => `<option value="${item.id}">${item.name}</option>`).join("")}
            </select>
          </div>
          <div class="field-panel">
            <label for="reason">推理理由</label>
            <textarea id="reason" placeholder="写下你的推理链：为什么不是胶囊，而是小药盒本身？"></textarea>
          </div>
          <button class="primary-button" data-action="submit-verdict" type="button">提交结案</button>
        </div>
      </aside>
    </section>
  `;
}

function renderEnding() {
  const finale = sceneById("finale");
  const verdict = state.verdict || {
    title: "尚未结案",
    score: "-",
    body: "请先提交指控。",
    ending: "NONE"
  };
  return `
    <section class="ending-stage">
      <img class="scene-image" src="${finale.assetPath}" alt="${finale.name}" />
      <div class="ending-copy">
        <p class="eyebrow">Ending · ${verdict.score}</p>
        <h1>${verdict.title}</h1>
        <p>${verdict.body}</p>
        ${
          verdict.ending === "TRUE_ENDING"
            ? `<p><b>侦探：</b>“真正的关键不是哪一颗胶囊，而是这个药盒本身。”</p>`
            : `<p>案件还没有被真正钉死。回到推理板，重新检查小药盒、指纹和 11:30 时间线。</p>`
        }
        <div class="action-row">
          <button class="primary-button" data-action="new-session" type="button">重新开始</button>
          <button class="plain-button" data-screen="board" type="button">回到推理板</button>
        </div>
      </div>
    </section>
  `;
}

function importanceText(level) {
  return { normal: "普通", important: "重要", decisive: "决定性" }[level] || "线索";
}

function bindEvents() {
  document.querySelectorAll("[data-action='home']").forEach((button) => {
    button.addEventListener("click", goHome);
  });
  document.querySelectorAll("[data-action='start-demo']").forEach((button) => {
    button.addEventListener("click", startSession);
  });
  document.querySelector("[data-action='retry']")?.addEventListener("click", startSession);
  document.querySelector("[data-action='new-session']")?.addEventListener("click", startSession);

  document.querySelectorAll("[data-screen]").forEach((button) => {
    button.addEventListener("click", () => setScreen(button.dataset.screen));
  });

  document.querySelectorAll("[data-scene]").forEach((button) => {
    button.addEventListener("click", () => {
      state.sceneId = button.dataset.scene;
      render();
    });
  });

  document.querySelectorAll("[data-evidence]").forEach((button) => {
    button.addEventListener("click", () => {
      state.selectedEvidenceId = button.dataset.evidence;
      render();
    });
  });

  document.querySelectorAll("[data-inspect]").forEach((button) => {
    button.addEventListener("click", async () => {
      try {
        const result = await api("/evidence/inspect", {
          method: "POST",
          body: JSON.stringify({ sessionId: state.sessionId, evidenceId: button.dataset.inspect })
        });
        mergeUnlocked(result.unlockedEvidenceIds);
        showToast(result.message);
      } catch (error) {
        showToast("检查证物失败，请确认后端运行正常。");
      }
    });
  });

  document.querySelector("[data-action='inspect-scene']")?.addEventListener("click", async () => {
    try {
      const result = await api(`/scene/${state.sceneId}/inspect`, {
        method: "POST",
        body: JSON.stringify({ sessionId: state.sessionId, evidenceId: "" })
      });
      mergeUnlocked(result.unlockedEvidenceIds);
      showToast(result.message);
    } catch (error) {
      showToast("搜查场景失败，请确认后端运行正常。");
    }
  });

  document.querySelectorAll("[data-use-evidence]").forEach((button) => {
    button.addEventListener("click", () => {
      state.evidenceToShow = button.dataset.useEvidence;
      state.screen = "interrogate";
      render();
    });
  });

  document.querySelectorAll("[data-suspect]").forEach((button) => {
    button.addEventListener("click", () => {
      state.selectedSuspectId = button.dataset.suspect;
      render();
    });
  });

  document.querySelectorAll("[data-show-evidence]").forEach((button) => {
    button.addEventListener("click", () => {
      state.evidenceToShow = button.dataset.showEvidence;
      render();
    });
  });

  document.querySelectorAll("[data-suggest]").forEach((button) => {
    button.addEventListener("click", () => {
      const input = document.querySelector("input[name='question']");
      input.value = button.dataset.suggest;
      input.focus();
    });
  });

  document.querySelector("[data-form='question']")?.addEventListener("submit", async (event) => {
    event.preventDefault();
    const input = event.currentTarget.querySelector("input[name='question']");
    const question = input.value.trim();
    if (!question || state.busy) return;
    const suspect = suspectById(state.selectedSuspectId);
    const shownName = state.evidenceToShow ? evidenceById(state.evidenceToShow).name : "";
    state.dialogue.push({
      type: "detective",
      speaker: shownName ? `侦探 · 出示「${shownName}」` : "侦探",
      text: question
    });
    const answerIndex = state.dialogue.push({
      type: "npc",
      speaker: `${suspect.name} · 生成中`,
      text: ""
    }) - 1;
    input.value = "";
    state.busy = true;
    render();
    try {
      await streamApi(
        "/interrogate/stream",
        {
          sessionId: state.sessionId,
          suspectId: suspect.id,
          question,
          shownEvidenceId: state.evidenceToShow || null
        },
        {
          meta(payload) {
            state.dialogue[answerIndex].speaker = `${suspect.name} · ${payload.mood || "生成中"}`;
            mergeUnlocked(payload.unlockedEvidenceIds || []);
            render();
          },
          delta(payload) {
            state.dialogue[answerIndex].text += payload.text || "";
            render();
          },
          replace(payload) {
            state.dialogue[answerIndex].text = payload.text || "";
            render();
          },
          done(payload) {
            state.dialogue[answerIndex].speaker = `${suspect.name} · ${payload.mood || "normal"}`;
            state.dialogue[answerIndex].text = payload.answer || state.dialogue[answerIndex].text;
            mergeUnlocked(payload.unlockedEvidenceIds || []);
          }
        }
      );
    } catch (error) {
      state.dialogue[answerIndex] = {
        type: "system",
        speaker: "系统",
        text: "审问请求失败，请确认后端、大模型配置和流式接口正常。"
      };
    } finally {
      state.busy = false;
      render();
    }
  });

  document.querySelector("[data-action='analyze']")?.addEventListener("click", async () => {
    try {
      const result = await api("/notes/analyze", {
        method: "POST",
        body: JSON.stringify({ sessionId: state.sessionId })
      });
      state.notes = result.notes || [];
      render();
    } catch (error) {
      showToast("整理矛盾失败，请确认后端运行正常。");
    }
  });

  document.querySelector("[data-action='submit-verdict']")?.addEventListener("click", async () => {
    const accused = document.querySelector("#accused").value;
    const keyItem = document.querySelector("#keyItem").value;
    const reason = document.querySelector("#reason").value.trim();
    if (!reason) {
      showToast("先写下推理理由，再提交结案。");
      return;
    }
    try {
      state.verdict = await api("/verdict", {
        method: "POST",
        body: JSON.stringify({ sessionId: state.sessionId, accused, keyItem, reason })
      });
      state.screen = "ending";
      render();
    } catch (error) {
      showToast("结案提交失败，请确认后端运行正常。");
    }
  });
}

render();
