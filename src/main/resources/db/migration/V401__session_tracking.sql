-- Lịch sử đăng nhập cho trang quản trị: IP, thiết bị và lần hoạt động cuối của mỗi phiên.
-- Đây là dữ liệu cá nhân: phải nêu trong chính sách quyền riêng tư; lịch giữ/xóa do vận hành quyết định.
ALTER TABLE access_session
    ADD COLUMN ip_address   text,
    ADD COLUMN user_agent   text,
    ADD COLUMN last_seen_at timestamptz;

CREATE INDEX ix_access_session_user_created ON access_session (user_id, created_at DESC);
