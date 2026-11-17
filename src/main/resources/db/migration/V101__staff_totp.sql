-- A: thứ hai ngoài email OTP cho tài khoản nhân viên (NFR-05).
-- Secret chỉ lưu ciphertext AES-GCM; không có API công khai đọc hoặc tự enroll.
CREATE TABLE staff_mfa (
    user_id uuid PRIMARY KEY REFERENCES app_user(id),
    secret_ciphertext text NOT NULL,
    last_step bigint NOT NULL DEFAULT -1,
    enrolled_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE staff_mfa ENABLE ROW LEVEL SECURITY;
