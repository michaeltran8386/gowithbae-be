-- Người 3 (C): bảng lưu Idempotency-Key cho createChoiceSession, finalizeChoiceSession, createDatePlan (NFR-08).
-- Tuân thủ V3 & RowLevelSecurityTest: bật RLS và thu hồi toàn bộ quyền từ anon/authenticated.

CREATE TABLE idempotency_record (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_key       text NOT NULL,
    operation       text NOT NULL,
    idem_key        text NOT NULL,
    request_hash    text NOT NULL,
    response_status int  NOT NULL,
    response_body   jsonb NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL DEFAULT (now() + interval '24 hours'),
    UNIQUE (actor_key, operation, idem_key)
);

CREATE INDEX ix_idempotency_record_expires ON idempotency_record (expires_at);

ALTER TABLE idempotency_record ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE idempotency_record FROM anon, authenticated;
