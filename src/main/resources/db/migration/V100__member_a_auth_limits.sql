-- A owns V100–199. Rate-limit keys are HMAC, never raw email/IP.
CREATE TABLE request_rate_limit (
    bucket text NOT NULL,
    key_hash text NOT NULL,
    window_start timestamptz NOT NULL,
    hits int NOT NULL CHECK (hits > 0),
    PRIMARY KEY (bucket, key_hash, window_start)
);
CREATE INDEX ix_request_rate_limit_window ON request_rate_limit(window_start);
ALTER TABLE request_rate_limit ENABLE ROW LEVEL SECURITY;
CREATE INDEX ix_login_challenge_user ON login_challenge(user_id, purpose, created_at DESC);
