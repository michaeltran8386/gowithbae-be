-- Flyway callback (không có số phiên bản, không có checksum), chạy trước mỗi lần migrate.
--
-- Supabase luôn có sẵn role anon và authenticated; một số migration (V300) REVOKE quyền của hai role này
-- mà không kiểm tra trước. Trên Postgres thường (Docker, Testcontainers, máy dev) hai role không tồn tại
-- nên migration lỗi và ứng dụng không khởi động.
--
-- Tạo hai role rỗng, không đăng nhập được, chỉ khi chưa có. Trên Supabase khối này không làm gì.
-- Dùng callback thay vì sửa V300 vì V300 đã chạy trên Supabase của team: sửa nội dung sẽ lệch checksum.
DO $$
DECLARE r text;
BEGIN
    FOREACH r IN ARRAY ARRAY['anon', 'authenticated'] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
            EXECUTE format('CREATE ROLE %I NOLOGIN', r);
        END IF;
    END LOOP;
END $$;
