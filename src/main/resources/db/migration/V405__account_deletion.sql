-- Khôi phục và xóa tài khoản (ẩn danh hóa). Trạng thái mới DELETED là cuối: dòng app_user được giữ lại
-- (nhật ký, báo sai, địa điểm vẫn trỏ tới) nhưng không còn email/tên thật.
-- CHECK trạng thái cũ khai báo inline ở V1 (tên do Postgres tự đặt): tìm theo nội dung để xóa cho chắc.
DO $$
DECLARE
    c text;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint
             WHERE conrelid = 'app_user'::regclass AND contype = 'c'
               AND pg_get_constraintdef(oid) LIKE '%DELETION_REQUESTED%'
    LOOP
        EXECUTE format('ALTER TABLE app_user DROP CONSTRAINT %I', c);
    END LOOP;
END $$;
ALTER TABLE app_user ADD CONSTRAINT app_user_status_check
    CHECK (status IN ('ACTIVE', 'LOCKED', 'DELETION_REQUESTED', 'DELETED'));

ALTER TABLE app_user ADD COLUMN deletion_requested_at timestamptz;
ALTER TABLE app_user ADD COLUMN deleted_at timestamptz;

-- Tài khoản đã yêu cầu xóa trước migration: lấy lần cập nhật cuối làm thời điểm yêu cầu (đếm 30 ngày từ đó).
UPDATE app_user SET deletion_requested_at = updated_at WHERE status = 'DELETION_REQUESTED';

ALTER TABLE app_user ADD CONSTRAINT ck_app_user_deletion_requested_at
    CHECK (status <> 'DELETION_REQUESTED' OR deletion_requested_at IS NOT NULL) NOT VALID;
ALTER TABLE app_user VALIDATE CONSTRAINT ck_app_user_deletion_requested_at;
ALTER TABLE app_user ADD CONSTRAINT ck_app_user_deleted_at
    CHECK ((status = 'DELETED') = (deleted_at IS NOT NULL));

-- Job tự xóa tìm tài khoản yêu cầu xóa quá hạn.
CREATE INDEX ix_app_user_deletion_due ON app_user (deletion_requested_at) WHERE status = 'DELETION_REQUESTED';
