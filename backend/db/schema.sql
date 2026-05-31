-- AI detective game schema for PostgreSQL.
-- Run this manually in the target database before backend implementation.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS cases (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    subtitle TEXT,
    description TEXT,
    opening_text TEXT,
    truth_summary TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS case_scenes (
    id TEXT PRIMARY KEY,
    case_id TEXT NOT NULL REFERENCES cases(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    asset_path TEXT NOT NULL,
    description TEXT,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_case_scenes_case_id_sort
    ON case_scenes(case_id, sort_order);

CREATE TABLE IF NOT EXISTS case_suspects (
    id TEXT PRIMARY KEY,
    case_id TEXT NOT NULL REFERENCES cases(id) ON DELETE CASCADE,
    scene_id TEXT REFERENCES case_scenes(id) ON DELETE SET NULL,
    name TEXT NOT NULL,
    role TEXT NOT NULL,
    portrait_path TEXT NOT NULL,
    public_description TEXT NOT NULL,
    personality TEXT NOT NULL,
    hidden_truth TEXT NOT NULL,
    system_prompt TEXT NOT NULL,
    quick_questions JSONB NOT NULL DEFAULT '[]'::jsonb,
    is_witness BOOLEAN NOT NULL DEFAULT FALSE,
    is_true_culprit BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_case_suspects_case_id_sort
    ON case_suspects(case_id, sort_order);

CREATE TABLE IF NOT EXISTS case_evidence (
    id TEXT PRIMARY KEY,
    case_id TEXT NOT NULL REFERENCES cases(id) ON DELETE CASCADE,
    scene_id TEXT REFERENCES case_scenes(id) ON DELETE SET NULL,
    name TEXT NOT NULL,
    asset_path TEXT,
    short_description TEXT NOT NULL,
    full_description TEXT NOT NULL,
    importance TEXT NOT NULL CHECK (importance IN ('normal', 'important', 'decisive')),
    initial_unlocked BOOLEAN NOT NULL DEFAULT FALSE,
    tags JSONB NOT NULL DEFAULT '[]'::jsonb,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_case_evidence_case_id_sort
    ON case_evidence(case_id, sort_order);

CREATE INDEX IF NOT EXISTS idx_case_evidence_tags_gin
    ON case_evidence USING GIN(tags);

CREATE TABLE IF NOT EXISTS case_timeline_events (
    id TEXT PRIMARY KEY,
    case_id TEXT NOT NULL REFERENCES cases(id) ON DELETE CASCADE,
    time_label TEXT NOT NULL,
    description TEXT NOT NULL,
    required_evidence_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_case_timeline_case_id_sort
    ON case_timeline_events(case_id, sort_order);

CREATE TABLE IF NOT EXISTS game_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id TEXT NOT NULL REFERENCES cases(id) ON DELETE RESTRICT,
    player_name TEXT,
    status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'completed', 'abandoned')),
    current_screen TEXT NOT NULL DEFAULT 'opening',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_game_sessions_case_id_created
    ON game_sessions(case_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_game_sessions_status
    ON game_sessions(status);

CREATE TABLE IF NOT EXISTS session_unlocked_evidence (
    session_id UUID NOT NULL REFERENCES game_sessions(id) ON DELETE CASCADE,
    evidence_id TEXT NOT NULL REFERENCES case_evidence(id) ON DELETE CASCADE,
    unlocked_by_type TEXT NOT NULL DEFAULT 'system'
        CHECK (unlocked_by_type IN ('system', 'scene', 'evidence', 'interrogation', 'note', 'manual')),
    unlocked_by_id TEXT,
    unlocked_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (session_id, evidence_id)
);

CREATE INDEX IF NOT EXISTS idx_session_unlocked_evidence_session
    ON session_unlocked_evidence(session_id, unlocked_at DESC);

CREATE TABLE IF NOT EXISTS dialogue_turns (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES game_sessions(id) ON DELETE CASCADE,
    suspect_id TEXT NOT NULL REFERENCES case_suspects(id) ON DELETE RESTRICT,
    question TEXT NOT NULL,
    shown_evidence_id TEXT REFERENCES case_evidence(id) ON DELETE SET NULL,
    answer TEXT NOT NULL,
    mood TEXT NOT NULL DEFAULT 'normal',
    unlocked_evidence_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    prompt_snapshot JSONB,
    model_name TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_dialogue_turns_session_created
    ON dialogue_turns(session_id, created_at ASC);

CREATE INDEX IF NOT EXISTS idx_dialogue_turns_suspect
    ON dialogue_turns(suspect_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_dialogue_turns_unlocked_gin
    ON dialogue_turns USING GIN(unlocked_evidence_ids);

CREATE TABLE IF NOT EXISTS investigation_notes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES game_sessions(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    source TEXT NOT NULL DEFAULT 'mock' CHECK (source IN ('mock', 'ai', 'system', 'player')),
    related_evidence_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    related_suspect_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_investigation_notes_session_created
    ON investigation_notes(session_id, created_at DESC);

CREATE TABLE IF NOT EXISTS verdicts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES game_sessions(id) ON DELETE CASCADE,
    accused_suspect_id TEXT NOT NULL REFERENCES case_suspects(id) ON DELETE RESTRICT,
    key_item_id TEXT NOT NULL REFERENCES case_evidence(id) ON DELETE RESTRICT,
    reason TEXT NOT NULL,
    ending TEXT NOT NULL CHECK (ending IN ('TRUE_ENDING', 'RIGHT_KILLER_WEAK', 'FALSE_ACCUSATION')),
    score TEXT NOT NULL CHECK (score IN ('S', 'A', 'B', 'C')),
    finale_title TEXT NOT NULL,
    finale_text TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_verdicts_session_id
    ON verdicts(session_id);

CREATE TABLE IF NOT EXISTS ai_call_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID REFERENCES game_sessions(id) ON DELETE SET NULL,
    purpose TEXT NOT NULL CHECK (purpose IN ('interrogate', 'analyze_notes', 'verdict', 'other')),
    model_name TEXT,
    request_json JSONB,
    response_json JSONB,
    error_message TEXT,
    latency_ms INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ai_call_logs_session_created
    ON ai_call_logs(session_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ai_call_logs_purpose_created
    ON ai_call_logs(purpose, created_at DESC);
