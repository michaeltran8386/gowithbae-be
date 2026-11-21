-- Link mời người ấy xem kế hoạch tạo tay (không qua cùng chọn) và trả lời có đi hay không.
-- Mỗi kế hoạch có tối đa một link đang hiệu lực; chỉ lưu hash của token (giống share_grant, RULE-12).
-- Hết hạn tính động: 1 ngày sau date_plan.scheduled_at (đổi giờ hẹn thì hạn đổi theo).
CREATE TABLE date_plan_invite (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id       uuid NOT NULL REFERENCES date_plan(id) ON DELETE CASCADE,
    token_hash    text NOT NULL UNIQUE,
    created_at    timestamptz NOT NULL DEFAULT now(),
    revoked_at    timestamptz,
    response      text CHECK (response IN ('GOING', 'NOT_GOING')),
    guest_name    text CHECK (guest_name IS NULL OR char_length(guest_name) BETWEEN 1 AND 50),
    responded_at  timestamptz,
    CHECK ((response IS NULL) = (responded_at IS NULL))
);

CREATE UNIQUE INDEX ux_date_plan_invite_active ON date_plan_invite(plan_id) WHERE revoked_at IS NULL;

ALTER TABLE date_plan_invite ENABLE ROW LEVEL SECURITY;
