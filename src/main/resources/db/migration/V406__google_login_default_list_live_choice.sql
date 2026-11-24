-- CR-002 (feedback sau launch, 2026-11-24)

-- Đăng nhập Google: lưu `sub` của Google để nhận lại tài khoản kể cả khi người dùng đổi email.
ALTER TABLE app_user ADD COLUMN google_sub text;
CREATE UNIQUE INDEX ux_app_user_google_sub ON app_user (google_sub) WHERE google_sub IS NOT NULL;

-- Danh sách "Đã lưu" (nút tim): mỗi người đúng một danh sách mặc định, không đổi tên/xóa.
ALTER TABLE saved_list ADD COLUMN is_default boolean NOT NULL DEFAULT false;
CREATE UNIQUE INDEX ux_saved_list_default_owner ON saved_list (owner_id) WHERE is_default;

-- Phiên cùng chọn gắn với danh sách: lựa chọn đồng bộ theo danh sách, nơi bị bỏ khỏi danh sách
-- chỉ ẩn (active = false) để phiếu cũ giữ nguyên nghĩa.
ALTER TABLE choice_session ADD COLUMN list_id uuid REFERENCES saved_list(id) ON DELETE SET NULL;
CREATE INDEX ix_choice_session_list ON choice_session (list_id) WHERE list_id IS NOT NULL;
ALTER TABLE choice_candidate ADD COLUMN active boolean NOT NULL DEFAULT true;

-- Không giới hạn số người được mời trong một phiên.
DROP INDEX IF EXISTS ux_recipient_session_active;
CREATE INDEX IF NOT EXISTS ix_recipient_session_session_active ON recipient_session (session_id) WHERE revoked_at IS NULL;
