-- GoWithBae G1 schema, từ mô hình dữ liệu logic §6 của GWB-REQ-G1 v1.0.
-- Enum lưu text + CHECK để đổi giá trị không cần ALTER TYPE.
-- Thời gian: timestamptz (UTC trong DB); nghiệp vụ hiển thị theo Asia/Ho_Chi_Minh.

-- ───────────── Tài khoản, phiên, phân quyền ─────────────
CREATE TABLE stored_file (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id   uuid,
    purpose         text NOT NULL CHECK (purpose IN ('AVATAR','PLACE_IMAGE','PLACE_REPORT','SUPPORT','COLLECTION')),
    content_type    text NOT NULL,
    size_bytes      bigint NOT NULL CHECK (size_bytes > 0),
    storage_key     text NOT NULL UNIQUE,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE app_user (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email           text NOT NULL,
    display_name    text NOT NULL,
    avatar_file_id  uuid REFERENCES stored_file(id),
    status          text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','LOCKED','DELETION_REQUESTED')),
    locked_reason   text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_app_user_email ON app_user (lower(email));
ALTER TABLE stored_file ADD CONSTRAINT fk_stored_file_owner FOREIGN KEY (owner_user_id) REFERENCES app_user(id);

-- Mã OTP chỉ lưu hash (RULE-16, NFR-05)
CREATE TABLE login_challenge (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email           text NOT NULL,
    purpose         text NOT NULL CHECK (purpose IN ('LOGIN','EMAIL_CHANGE')),
    user_id         uuid REFERENCES app_user(id),
    code_hash       text NOT NULL,
    attempts        int  NOT NULL DEFAULT 0,
    expires_at      timestamptz NOT NULL,
    used_at         timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_login_challenge_email ON login_challenge (lower(email), created_at DESC);

-- JWT mang claim sid = access_session.id; server kiểm tra revoked_at mỗi request (FR-22)
CREATE TABLE access_session (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid NOT NULL REFERENCES app_user(id),
    created_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    revoked_at      timestamptz
);
CREATE INDEX ix_access_session_user ON access_session (user_id) WHERE revoked_at IS NULL;

CREATE TABLE role (
    code            text PRIMARY KEY,
    name            text NOT NULL
);

CREATE TABLE user_role (
    user_id         uuid NOT NULL REFERENCES app_user(id),
    role_code       text NOT NULL REFERENCES role(code),
    granted_by      uuid REFERENCES app_user(id),
    granted_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role_code)
);

-- ───────────── Danh mục ─────────────
CREATE TABLE area (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code            text NOT NULL UNIQUE,
    name            text NOT NULL,
    status          text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DEPRECATED'))
);

CREATE TABLE taxonomy (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    group_code      text NOT NULL CHECK (group_code IN ('CATEGORY','ACTIVITY','VIBE','STYLE','SCENERY','FEATURE','AMENITY','OCCASION')),
    code            text NOT NULL,
    name            text NOT NULL,
    status          text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DEPRECATED')),
    UNIQUE (group_code, code)
);

-- ───────────── Địa điểm ─────────────
CREATE TABLE place (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code                    text NOT NULL UNIQUE,
    name                    text NOT NULL,
    search_text             text NOT NULL DEFAULT '',  -- tên/khu vực/nhãn đã bỏ dấu, lowercase; app cập nhật (FR-02)
    category_id             uuid NOT NULL REFERENCES taxonomy(id),
    area_id                 uuid NOT NULL REFERENCES area(id),
    address                 text NOT NULL,
    lat                     double precision NOT NULL CHECK (lat BETWEEN -90 AND 90),
    lng                     double precision NOT NULL CHECK (lng BETWEEN -180 AND 180),
    description             text,
    phone                   text,
    website                 text,
    notes                   text,
    reservation_recommended boolean,
    hours_status            text NOT NULL DEFAULT 'UNKNOWN' CHECK (hours_status IN ('KNOWN','UNKNOWN')),
    status                  text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PENDING','PUBLISHED','HIDDEN','INACTIVE')),
    status_reason           text,
    source                  text,
    verified_at             date,
    created_by              uuid REFERENCES app_user(id),
    reviewed_by             uuid REFERENCES app_user(id),
    published_at            timestamptz,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_place_status_area ON place (status, area_id);

CREATE TABLE place_price (
    place_id            uuid PRIMARY KEY REFERENCES place(id),
    status              text NOT NULL DEFAULT 'UNKNOWN' CHECK (status IN ('KNOWN','UNKNOWN')),
    standard_for_two    bigint,
    min_price           bigint,
    max_price           bigint,
    includes            text,
    source              text,
    verified_at         date,
    -- KNOWN ⇒ 0 ≤ min ≤ standard ≤ max (§6 PlacePrice)
    CHECK (status = 'UNKNOWN' OR (
        standard_for_two IS NOT NULL AND min_price IS NOT NULL AND max_price IS NOT NULL
        AND 0 <= min_price AND min_price <= standard_for_two AND standard_for_two <= max_price))
);
CREATE INDEX ix_place_price_standard ON place_price (standard_for_two) WHERE status = 'KNOWN';

CREATE TABLE opening_hours (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    place_id        uuid NOT NULL REFERENCES place(id),
    day_of_week     smallint NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    opens_at        time NOT NULL,
    closes_at       time NOT NULL  -- closes_at <= opens_at = qua đêm (OQ-04)
);
CREATE INDEX ix_opening_hours_place ON opening_hours (place_id, day_of_week);

CREATE TABLE date_exception (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    place_id        uuid NOT NULL REFERENCES place(id),
    on_date         date NOT NULL,
    closed          boolean NOT NULL,
    opens_at        time,
    closes_at       time,
    note            text,
    CHECK (closed OR (opens_at IS NOT NULL AND closes_at IS NOT NULL))
);
CREATE INDEX ix_date_exception_place ON date_exception (place_id, on_date);

CREATE TABLE place_image (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    place_id        uuid NOT NULL REFERENCES place(id),
    file_id         uuid NOT NULL REFERENCES stored_file(id),
    sort_order      int  NOT NULL DEFAULT 0,
    is_cover        boolean NOT NULL DEFAULT false,
    caption         text,
    source_rights   text NOT NULL
);
CREATE UNIQUE INDEX ux_place_image_cover ON place_image (place_id) WHERE is_cover;

CREATE TABLE place_tag (
    place_id        uuid NOT NULL REFERENCES place(id),
    taxonomy_id     uuid NOT NULL REFERENCES taxonomy(id),
    PRIMARY KEY (place_id, taxonomy_id)
);
CREATE INDEX ix_place_tag_taxonomy ON place_tag (taxonomy_id);

CREATE TABLE collection (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    title           text NOT NULL,
    body            text,
    cover_file_id   uuid REFERENCES stored_file(id),
    visible_from    timestamptz,
    visible_until   timestamptz,
    created_by      uuid REFERENCES app_user(id),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CHECK (visible_until IS NULL OR visible_from IS NULL OR visible_from < visible_until)
);

CREATE TABLE collection_item (
    collection_id   uuid NOT NULL REFERENCES collection(id) ON DELETE CASCADE,
    place_id        uuid NOT NULL REFERENCES place(id),
    sort_order      int  NOT NULL DEFAULT 0,
    PRIMARY KEY (collection_id, place_id)
);

-- ───────────── Danh sách đã lưu (RULE-07) ─────────────
CREATE TABLE saved_list (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id        uuid NOT NULL REFERENCES app_user(id),
    name            text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_saved_list_owner ON saved_list (owner_id);

CREATE TABLE saved_list_item (
    list_id         uuid NOT NULL REFERENCES saved_list(id) ON DELETE CASCADE,
    place_id        uuid NOT NULL REFERENCES place(id),
    added_at        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (list_id, place_id)
);

-- ───────────── Cùng chọn (RULE-08..12) ─────────────
CREATE TABLE choice_session (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    creator_id      uuid NOT NULL REFERENCES app_user(id),
    status          text NOT NULL DEFAULT 'WAITING' CHECK (status IN ('WAITING','VOTING','FINALIZED')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    finalized_at    timestamptz
);
CREATE INDEX ix_choice_session_creator ON choice_session (creator_id);

CREATE TABLE choice_candidate (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      uuid NOT NULL REFERENCES choice_session(id),
    place_id        uuid NOT NULL REFERENCES place(id),
    sort_order      int  NOT NULL DEFAULT 0,
    UNIQUE (session_id, place_id)
);

-- Link mời: chỉ lưu hash của token
CREATE TABLE share_grant (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      uuid NOT NULL REFERENCES choice_session(id),
    token_hash      text NOT NULL UNIQUE,
    expires_at      timestamptz NOT NULL,
    revoked_at      timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE recipient_session (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    share_grant_id  uuid NOT NULL REFERENCES share_grant(id),
    session_id      uuid NOT NULL REFERENCES choice_session(id),
    display_name    text NOT NULL,
    token_hash      text NOT NULL UNIQUE,
    created_at      timestamptz NOT NULL DEFAULT now(),
    revoked_at      timestamptz
);
-- Một suất người nhận còn hiệu lực/phiên; join đồng thời chỉ một bản ghi thắng (FR-11, NFR-08)
CREATE UNIQUE INDEX ux_recipient_session_active ON recipient_session (session_id) WHERE revoked_at IS NULL;

-- Thiếu bản ghi = Chưa trả lời, không phải Bỏ qua (RULE-10)
CREATE TABLE vote (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    candidate_id            uuid NOT NULL REFERENCES choice_candidate(id),
    voter_user_id           uuid REFERENCES app_user(id),
    voter_recipient_id      uuid REFERENCES recipient_session(id),
    preference              text NOT NULL CHECK (preference IN ('LIKE','SKIP')),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    CHECK ((voter_user_id IS NULL) <> (voter_recipient_id IS NULL))
);
CREATE UNIQUE INDEX ux_vote_user ON vote (candidate_id, voter_user_id) WHERE voter_user_id IS NOT NULL;
CREATE UNIQUE INDEX ux_vote_recipient ON vote (candidate_id, voter_recipient_id) WHERE voter_recipient_id IS NOT NULL;

-- ───────────── Kế hoạch (RULE-13..15) ─────────────
CREATE TABLE date_plan (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id                uuid NOT NULL REFERENCES app_user(id),
    place_id                uuid NOT NULL REFERENCES place(id),
    scheduled_at            timestamptz NOT NULL,
    note                    text,
    status                  text NOT NULL DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED','CANCELLED','PAST')),
    choice_session_id       uuid UNIQUE REFERENCES choice_session(id),  -- PROP: 1 phiên tạo tối đa 1 plan (OQ-08)
    recipient_session_id    uuid REFERENCES recipient_session(id),
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_date_plan_owner ON date_plan (owner_id, scheduled_at DESC);
CREATE INDEX ix_date_plan_due ON date_plan (scheduled_at) WHERE status = 'SCHEDULED';

CREATE TABLE visit_self_report (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id                 uuid NOT NULL REFERENCES date_plan(id),
    respondent_user_id      uuid REFERENCES app_user(id),
    respondent_recipient_id uuid REFERENCES recipient_session(id),
    answer                  text NOT NULL CHECK (answer IN ('YES','NO')),
    answered_at             timestamptz NOT NULL DEFAULT now(),
    CHECK ((respondent_user_id IS NULL) <> (respondent_recipient_id IS NULL))
);

-- ───────────── Báo sai, hỗ trợ, thông báo ─────────────
CREATE TABLE place_report (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    place_id            uuid NOT NULL REFERENCES place(id),
    reason              text NOT NULL CHECK (reason IN ('WRONG_PRICE','WRONG_HOURS','WRONG_ADDRESS','CLOSED','WRONG_IMAGE','OTHER')),
    description         text,
    file_id             uuid REFERENCES stored_file(id),
    sender_user_id      uuid REFERENCES app_user(id),
    sender_fingerprint  text,  -- hash IP/thiết bị để chống gửi hàng loạt, không lưu IP thô
    assignee_id         uuid REFERENCES app_user(id),
    status              text NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW','CHECKING','RESOLVED','INSUFFICIENT')),
    resolution_note     text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CHECK (status NOT IN ('RESOLVED','INSUFFICIENT') OR resolution_note IS NOT NULL)
);
CREATE INDEX ix_place_report_status ON place_report (status, created_at);

CREATE TABLE support_ticket (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id        uuid NOT NULL REFERENCES app_user(id),
    category        text NOT NULL CHECK (category IN ('ACCOUNT','PLACE_INFO','DATE_PLAN','OTHER')),
    subject         text NOT NULL,
    status          text NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW','IN_PROGRESS','WAITING_CUSTOMER','CLOSED')),
    assignee_id     uuid REFERENCES app_user(id),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_support_ticket_owner ON support_ticket (owner_id);
CREATE INDEX ix_support_ticket_status ON support_ticket (status, updated_at);

CREATE TABLE support_message (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id       uuid NOT NULL REFERENCES support_ticket(id),
    author_id       uuid NOT NULL REFERENCES app_user(id),
    visibility      text NOT NULL CHECK (visibility IN ('PUBLIC','INTERNAL')),
    body            text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_support_message_ticket ON support_message (ticket_id, created_at);

CREATE TABLE support_message_file (
    message_id      uuid NOT NULL REFERENCES support_message(id),
    file_id         uuid NOT NULL REFERENCES stored_file(id),
    PRIMARY KEY (message_id, file_id)
);

CREATE TABLE notification (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_id    uuid NOT NULL REFERENCES app_user(id),
    type            text NOT NULL,
    object_type     text,
    object_id       uuid,
    summary         text NOT NULL,
    read_at         timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_notification_recipient ON notification (recipient_id, created_at DESC);

-- ───────────── Vận hành ─────────────
-- Không UPDATE/DELETE qua ứng dụng (FR-31); không ghi bí mật vào before/after
CREATE TABLE audit_log (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id        uuid REFERENCES app_user(id),
    action          text NOT NULL,
    object_type     text NOT NULL,
    object_id       text,
    before_value    jsonb,
    after_value     jsonb,
    reason          text,
    trace_id        text,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_log_object ON audit_log (object_type, object_id, created_at DESC);

-- event_id là khóa chống trùng (RULE-25)
CREATE TABLE analytics_event (
    event_id        uuid PRIMARY KEY,
    name            text NOT NULL,
    occurred_at     timestamptz NOT NULL,
    received_at     timestamptz NOT NULL DEFAULT now(),
    session_id      text NOT NULL,
    platform        text NOT NULL,
    user_id         uuid,
    anonymous_id    text,
    object_id       text,
    props           jsonb NOT NULL DEFAULT '{}'::jsonb,
    CHECK (user_id IS NOT NULL OR anonymous_id IS NOT NULL)
);
CREATE INDEX ix_analytics_event_name_time ON analytics_event (name, occurred_at);

CREATE TABLE import_batch (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_by          uuid NOT NULL REFERENCES app_user(id),
    template_version    text NOT NULL,
    status              text NOT NULL CHECK (status IN ('VALIDATED','INVALID','COMMITTED')),
    total_rows          int  NOT NULL,
    error_rows          int  NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    committed_at        timestamptz
);

CREATE TABLE import_row_result (
    batch_id        uuid NOT NULL REFERENCES import_batch(id),
    row_number      int  NOT NULL,
    place_code      text,
    valid           boolean NOT NULL,
    errors          jsonb NOT NULL DEFAULT '[]'::jsonb,
    raw             jsonb NOT NULL,
    PRIMARY KEY (batch_id, row_number)
);
