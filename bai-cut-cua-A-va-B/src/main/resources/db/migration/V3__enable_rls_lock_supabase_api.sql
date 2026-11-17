-- Supabase mở schema public qua Data API (PostgREST) cho role anon/authenticated.
-- Ứng dụng chỉ truy cập DB qua Spring Boot, nên khóa đường REST đó:
--   1. Bật RLS, không tạo policy ⇒ anon/authenticated không đọc/ghi được dòng nào.
--      Backend kết nối bằng role owner của bảng nên không bị RLS chặn (không dùng FORCE).
--   2. Thu hồi quyền của anon/authenticated (chỉ khi các role này tồn tại, tức đang chạy trên Supabase).
-- Bảng tạo trong migration sau PHẢI tự `ALTER TABLE ... ENABLE ROW LEVEL SECURITY;`
-- (test RowLevelSecurityTest sẽ fail nếu quên).

DO $$
DECLARE
    t record;
BEGIN
    -- Bỏ qua flyway_schema_history: Flyway đang giữ lock bảng này khi chạy migration (ALTER sẽ treo).
    -- Bảng đó vẫn được khóa với anon/authenticated bằng REVOKE bên dưới.
    FOR t IN SELECT tablename FROM pg_tables
             WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history' LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t.tablename);
    END LOOP;
END $$;

DO $$
DECLARE
    r text;
BEGIN
    FOREACH r IN ARRAY ARRAY['anon', 'authenticated'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
            EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA public FROM %I', r);
            EXECUTE format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM %I', r);
            EXECUTE format('REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM %I', r);
            -- Bảng/sequence tạo sau này bởi role chạy migration cũng không cấp cho anon/authenticated
            EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON TABLES FROM %I', r);
            EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON SEQUENCES FROM %I', r);
            EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON FUNCTIONS FROM %I', r);
        END IF;
    END LOOP;
END $$;
