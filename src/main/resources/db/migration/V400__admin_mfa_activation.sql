-- Link kích hoạt TOTP dùng 1 lần cho tài khoản quản trị (thay cho provisioning bằng terminal).
-- Chỉ SUPER_ADMIN hoặc lệnh bootstrap tạo được link; token chỉ lưu hash, secret mã hóa AES-GCM như staff_mfa.
-- Đánh số 400+: Supabase của team đã áp tới V300, Flyway không nhận migration số nhỏ hơn.
CREATE TABLE mfa_activation (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             uuid NOT NULL REFERENCES app_user(id),
    token_hash          text NOT NULL UNIQUE,
    secret_ciphertext   text NOT NULL,
    attempts            int  NOT NULL DEFAULT 0,
    expires_at          timestamptz NOT NULL,
    used_at             timestamptz,
    created_by          uuid REFERENCES app_user(id),
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_mfa_activation_user ON mfa_activation (user_id) WHERE used_at IS NULL;
ALTER TABLE mfa_activation ENABLE ROW LEVEL SECURITY;
