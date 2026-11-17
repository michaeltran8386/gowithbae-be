-- CR-001 / RULE-29: một kế hoạch/người chỉ có một câu trả lời hiện tại.
-- Nếu dữ liệu cũ trùng, migration dừng; không tự xóa lịch sử trước khi chốt OQ-10/25.
CREATE UNIQUE INDEX ux_visit_report_user ON visit_self_report (plan_id, respondent_user_id)
    WHERE respondent_user_id IS NOT NULL;
CREATE UNIQUE INDEX ux_visit_report_recipient ON visit_self_report (plan_id, respondent_recipient_id)
    WHERE respondent_recipient_id IS NOT NULL;

-- Lời nhắc là trục riêng: DISMISSED không phải visit_answer=NO hoặc plan=CANCELLED.
CREATE TABLE date_reminder (
    id                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id                  uuid NOT NULL REFERENCES date_plan(id),
    recipient_user_id        uuid REFERENCES app_user(id),
    recipient_session_id     uuid REFERENCES recipient_session(id),
    scheduled_at             timestamptz NOT NULL,
    status                   text NOT NULL DEFAULT 'PENDING'
                             CHECK (status IN ('PENDING','SHOWN','DISMISSED','ANSWERED','CANCELLED')),
    dismissed_at             timestamptz,
    answered_at              timestamptz,
    created_at               timestamptz NOT NULL DEFAULT now(),
    updated_at               timestamptz NOT NULL DEFAULT now(),
    CHECK ((recipient_user_id IS NULL) <> (recipient_session_id IS NULL)),
    CHECK ((status = 'DISMISSED') = (dismissed_at IS NOT NULL)),
    CHECK ((status = 'ANSWERED') = (answered_at IS NOT NULL))
);
-- OQ-10/25: chưa tự cấp quyền cho actor hoặc tạo lại lời nhắc khi đổi giờ.
CREATE UNIQUE INDEX ux_date_reminder_user ON date_reminder (plan_id, recipient_user_id)
    WHERE recipient_user_id IS NOT NULL;
CREATE UNIQUE INDEX ux_date_reminder_recipient ON date_reminder (plan_id, recipient_session_id)
    WHERE recipient_session_id IS NOT NULL;
CREATE INDEX ix_date_reminder_due ON date_reminder (scheduled_at) WHERE status IN ('PENDING','SHOWN');
ALTER TABLE date_reminder ENABLE ROW LEVEL SECURITY;

-- PlaceVisitCount là dữ liệu tính toán từ lượt YES hợp lệ theo tài khoản/chi nhánh.
-- Không tạo bộ đếm ghi thêm hay view quyền owner có thể làm lộ dữ liệu qua Data API.
