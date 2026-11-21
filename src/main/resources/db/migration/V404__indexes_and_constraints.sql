-- Index cho các truy vấn đang chạy và ràng buộc dữ liệu chặt hơn (chốt ngày 21/11/2026).
-- Không đổi dữ liệu, không đổi API.

-- ───────────── 1. Tìm kiếm không dấu ─────────────
-- /places và /admin/places lọc bằng search_text LIKE '%từ khóa%': cần index trigram, B-tree không dùng được.
-- Supabase đã có sẵn extension (schema extensions nằm trong search_path); Postgres thường tạo vào public.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX ix_place_search_text_trgm ON place USING gin (search_text gin_trgm_ops);

-- ───────────── 2. Khóa ngoại được truy vấn ─────────────
-- Postgres không tự tạo index cho khóa ngoại. Chỉ thêm cho cột có truy vấn lọc/nối thật.
CREATE INDEX ix_place_category ON place (category_id);
CREATE INDEX ix_place_report_place ON place_report (place_id);
CREATE INDEX ix_place_report_assignee ON place_report (assignee_id) WHERE assignee_id IS NOT NULL;
CREATE INDEX ix_support_ticket_assignee ON support_ticket (assignee_id) WHERE assignee_id IS NOT NULL;
CREATE INDEX ix_place_image_file ON place_image (file_id);                 -- GET /files/{id}
CREATE INDEX ix_collection_cover_file ON collection (cover_file_id) WHERE cover_file_id IS NOT NULL;
CREATE INDEX ix_saved_list_item_place ON saved_list_item (place_id);
CREATE INDEX ix_collection_item_place ON collection_item (place_id);
CREATE INDEX ix_choice_candidate_place ON choice_candidate (place_id);
CREATE INDEX ix_date_plan_place ON date_plan (place_id);
CREATE INDEX ix_date_plan_recipient_session ON date_plan (recipient_session_id) WHERE recipient_session_id IS NOT NULL;
CREATE INDEX ix_share_grant_session ON share_grant (session_id);
CREATE INDEX ix_recipient_session_grant ON recipient_session (share_grant_id);

-- ───────────── 3. Hoạt động người dùng (admin) ─────────────
-- GET /admin/users/{id}/activity đọc 50 sự kiện gần nhất theo user_id.
CREATE INDEX ix_analytics_event_user ON analytics_event (user_id, occurred_at DESC) WHERE user_id IS NOT NULL;

-- ───────────── 4. Nhật ký ─────────────
-- /admin/audit-logs sắp theo thời gian, lọc theo người thao tác; lịch sử thông báo admin lọc theo action.
CREATE INDEX ix_audit_log_created ON audit_log (created_at DESC, id DESC);
CREATE INDEX ix_audit_log_actor ON audit_log (actor_id, created_at DESC) WHERE actor_id IS NOT NULL;
CREATE INDEX ix_audit_log_action ON audit_log (action, created_at DESC);

-- ───────────── 5 & 7. Ràng buộc dữ liệu ─────────────
-- Thêm NOT VALID rồi thử VALIDATE: dữ liệu mới luôn bị kiểm tra; nếu DB đã có dòng cũ vi phạm
-- (vd. Supabase nhóm) migration vẫn chạy và báo NOTICE để dọn rồi VALIDATE tay.
ALTER TABLE notification ADD CONSTRAINT ck_notification_type CHECK (type IN (
    'DATE_PLAN_UPDATED', 'DATE_PLAN_CANCELLED', 'DATE_PLAN_INVITE_RESPONDED',
    'SUPPORT_REPLIED', 'SUPPORT_STATUS_CHANGED', 'ADMIN_ANNOUNCEMENT')) NOT VALID;
ALTER TABLE place ADD CONSTRAINT ck_place_name_not_blank CHECK (btrim(name) <> '') NOT VALID;
ALTER TABLE place_image ADD CONSTRAINT ck_place_image_rights_not_blank CHECK (btrim(source_rights) <> '') NOT VALID;
ALTER TABLE support_message ADD CONSTRAINT ck_support_message_body_not_blank CHECK (btrim(body) <> '') NOT VALID;
ALTER TABLE saved_list ADD CONSTRAINT ck_saved_list_name_not_blank CHECK (btrim(name) <> '') NOT VALID;

DO $$
DECLARE
    c record;
BEGIN
    FOR c IN SELECT * FROM (VALUES
            ('notification', 'ck_notification_type'),
            ('place', 'ck_place_name_not_blank'),
            ('place_image', 'ck_place_image_rights_not_blank'),
            ('support_message', 'ck_support_message_body_not_blank'),
            ('saved_list', 'ck_saved_list_name_not_blank')) AS t(tbl, con)
    LOOP
        BEGIN
            EXECUTE format('ALTER TABLE %I VALIDATE CONSTRAINT %I', c.tbl, c.con);
        EXCEPTION WHEN check_violation THEN
            RAISE NOTICE 'V404: % có dòng cũ vi phạm %; ràng buộc vẫn áp cho dữ liệu mới. Dọn rồi chạy: ALTER TABLE % VALIDATE CONSTRAINT %',
                c.tbl, c.con, c.tbl, c.con;
        END;
    END LOOP;
END $$;

-- ───────────── 6. Nhật ký không sửa, không xóa (FR-31) ─────────────
-- Chặn UPDATE/DELETE từng dòng kể cả khi có người chạy SQL tay. TRUNCATE (dùng trong test) không bị chặn.
CREATE FUNCTION audit_log_immutable() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog
    AS $$
BEGIN
    RAISE EXCEPTION 'audit_log chỉ được thêm, không được sửa hoặc xóa' USING ERRCODE = 'restrict_violation';
END $$;

CREATE TRIGGER trg_audit_log_immutable
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_immutable();
