# Luồng chức năng — Backend GoWithBae G1

Tài liệu cho thành viên mới: hệ thống đang làm được gì, mỗi luồng gọi API nào theo thứ tự nào, quy tắc nào chặn ở server, và code nằm ở đâu. Chi tiết request/response xem `api/openapi.yaml` (contract 0.5.0); cách chạy và quy ước code xem [DEVELOPMENT.md](DEVELOPMENT.md).

Mọi đường dẫn bên dưới có tiền tố `/api/v1`. Lỗi trả `application/problem+json` (`status`, `detail`, có thể có `code`, `errors[]` theo trường).

## 0. Ai phụ trách phần nào

| Người | Phạm vi | Package chính |
|---|---|---|
| A | Đăng nhập, hồ sơ, danh sách lưu, thông báo, upload, analytics, tài khoản/nhật ký/báo cáo admin | `auth`, `me`, `savedlist`, `notification`, `upload`, `analytics`, `adminaccount`, `platform` |
| B | Địa điểm, khám phá, so sánh, ảnh công khai, quản trị địa điểm/nhãn/khu vực/bộ sưu tập/CSV, nội dung web | `place`, `admin/place`, `admin/*`, `content`, `common/files` |
| C | Cùng chọn, kế hoạch hẹn, hỗ trợ, báo sai địa điểm | `choice`, `plan`, `support`, `report` |

Module giao tiếp qua interface, không đọc chéo bảng của nhau: `AuditService`, `NotificationService`, `FileService` (A); `PlaceLookupService`, `PlaceReader` (B); `StatisticsContributor` (B và C nộp số liệu cho báo cáo của A).

## 1. Vai trò và đăng nhập

Vai trò: `MEMBER` (mặc định), `SUPER_ADMIN` (toàn quyền quản trị). `EDITOR`, `REVIEWER`, `SUPPORT` vẫn có trong DB và API nhưng chưa dùng ở giao diện.

### 1.1 Thành viên: email OTP

```
POST /auth/otp/request {email}        → 202 (luôn 202, không lộ email có tồn tại/bị khóa)
POST /auth/otp/verify  {email, code}  → 200 {accessToken, user}
```

- Lần đầu verify thì tự tạo tài khoản. Tài khoản bị khóa không nhận được mã.
- JWT HS256 có `sub` (user id), `roles`, `sid` (dòng `access_session`). Mỗi request kiểm tra phiên còn hiệu lực, trạng thái tài khoản và vai trò lấy từ DB.
- Phiên lưu IP (`X-Forwarded-For` do nginx gửi), trình duyệt, `last_seen_at` (cập nhật tối đa 5 phút/lần).
- `POST /auth/logout` thu hồi phiên hiện tại.

### 1.2 Quản trị viên: OTP + mã Authenticator (TOTP)

```
POST /auth/otp/verify {email, code}             → 401 code=STAFF_CODE_REQUIRED  (OTP đúng, chưa gửi mã TOTP; OTP chưa bị tiêu)
POST /auth/otp/verify {email, code, staffCode}  → 200 / 400 nếu mã TOTP sai
                                                → 401 code=MFA_NOT_ENROLLED nếu chưa cài Authenticator
```

### 1.3 Cài Authenticator bằng link dùng một lần

```
(admin) POST /admin/users {email, displayName, roles:[SUPER_ADMIN]} → activation.activationUrl
(admin) POST /admin/users/{id}/mfa-activation                       → link mới (mất điện thoại, link hết hạn)
(public) GET  /auth/mfa-activation/{token}         → email + otpauthUri (frontend vẽ QR)
(public) POST /auth/mfa-activation/{token} {code}  → bật TOTP, thu hồi phiên cũ của người đó
```

- Link hết hạn sau 24 giờ, sai tối đa 5 lần, cấp link mới thì link cũ mất hiệu lực. Chỉ lưu hash của token; secret mã hóa AES-GCM bằng `AUTH_MFA_KEY`.
- Admin đầu tiên: chạy image với `--app.admin.bootstrap-email=<email>` (xem DEVELOPMENT.md §10.1).
- URL link = `APP_WEB_BASE_URL` + `/admin/activate/<token>`.

## 2. Khám phá địa điểm (B, công khai)

| Việc | API | Ghi chú |
|---|---|---|
| Tìm và lọc | `GET /places?q=&areaId=&budget=&tagIds=&sort=&lat=&lng=` | `q` tìm không dấu (`search_text`). Nhiều `tagIds` = phải khớp tất cả. Chỉ trả địa điểm `PUBLISHED` |
| Chi tiết | `GET /places/{id}` | Giá cho 2 người (`KNOWN`/`UNKNOWN`), giờ mở cửa theo tuần + ngoại lệ, nhãn, nguồn/ngày kiểm tra |
| So sánh | `GET /places/compare?ids=` | Tối đa 3 (RULE-06). Thiếu dữ liệu trả "chưa có", không đổi thành "không có" |
| Danh mục | `GET /areas`, `GET /taxonomies` | Chỉ mục `ACTIVE` |
| Bộ sưu tập | `GET /collections`, `GET /collections/{id}` | Chỉ trong khung thời gian hiển thị, chỉ địa điểm đang hiển thị |
| Ảnh | `GET /files/{fileId}` | Chỉ phục vụ ảnh đã gắn vào địa điểm hoặc ảnh bìa bộ sưu tập; tệp khác 404. Cache 1 ngày |
| Nội dung web | `GET /site-content` | Hero, banner, FAQ, điều khoản, quyền riêng tư, liên hệ, chân trang. Cache 60 giây |

## 3. Luồng của thành viên

### 3.1 Danh sách đã lưu (A)

`GET/POST /saved-lists`, `GET/PATCH/DELETE /saved-lists/{id}`, `PUT/DELETE /saved-lists/{id}/places/{placeId}`. Chỉ lưu được địa điểm đang hiển thị (A gọi `PlaceLookupService` của B).

### 3.2 Cùng chọn — rủ người ấy chọn nơi đi (C)

```
1. Chủ:      POST /choice-sessions {placeIds: 2–5 nơi trong danh sách đã lưu}
             → shareGrant.inviteUrl  (link chỉ trả MỘT lần; server chỉ lưu hash)
2. Người ấy: GET  /share-grants/{inviteToken}        → xem lời mời, không cần đăng nhập
             POST /share-grants/{inviteToken}/join   → nhận X-Recipient-Session (mỗi link chỉ 1 người, người sau → SLOT_TAKEN)
3. Hai bên:  PUT  /choice-sessions/{id}/votes/{candidateId} {preference: LIKE | SKIP}
4. Chủ:      GET  /choice-sessions/{id}  → kết quả
             POST /choice-sessions/{id}/finalize {candidateId, scheduledAt}
             → chốt nơi cuối cùng VÀ tạo kế hoạch hẹn gắn với phiên; người ấy xem được kế hoạch này.
               Sau khi chốt không đổi phiếu được (RULE-11).
```

- Thu hồi link: `DELETE /choice-sessions/{id}/share-grants/{grantId}`; bỏ người tham gia: `DELETE .../recipient`.
- URL link mời = `APP_INVITE_BASE_URL` (phải kèm `/invite`) + `/<token>`; để trống thì suy ra từ request.
- Kế hoạch tạo thẳng ở `POST /date-plans {placeId, scheduledAt, note?}` không có người nhận; muốn mời thì dùng link mời xem kế hoạch (§3.4).

### 3.3 Kế hoạch hẹn (C)

`GET/POST /date-plans`, `GET/PATCH /date-plans/{id}`, `POST .../cancel`, `GET .../calendar.ics` (thêm vào lịch). Sau giờ hẹn: lời nhắc "Đi chưa?" (`GET/PUT .../reminder`), xác nhận đã đi (`PUT .../visit-report`), số lần đã đi (`GET /me/places/{placeId}/visit-count`). Sửa hoặc hủy thì gửi thông báo cho bên còn lại.

### 3.4 Mời người ấy xem kế hoạch tạo tay (C)

```
Chủ:      POST   /date-plans/{id}/invite   → {inviteUrl, expiresAt}  (link trả MỘT lần; tạo lại thì link cũ hết hiệu lực, câu trả lời cũ bị xóa)
          GET    /date-plans/{id}/invite   → trạng thái: đã trả lời chưa, GOING | NOT_GOING, tên người trả lời (404 nếu chưa có link)
          DELETE /date-plans/{id}/invite   → thu hồi
Người ấy: GET    /plan-invites/{token}                 → giờ, địa điểm, trạng thái kế hoạch; KHÔNG có ghi chú riêng; không cần tài khoản
          PUT    /plan-invites/{token}/response {response: GOING | NOT_GOING, guestName?}  → chủ nhận thông báo DATE_PLAN_INVITE_RESPONDED
          GET    /plan-invites/{token}/calendar.ics    → thêm vào lịch
```

- Chỉ mời được kế hoạch của mình, đang `SCHEDULED` và chưa qua giờ (409). Mỗi kế hoạch tối đa một link đang hiệu lực (bảng `date_plan_invite`, V403, chỉ lưu hash token).
- Link hết hạn 1 ngày sau giờ hẹn (đổi giờ thì hạn đổi theo), khi bị thu hồi, hoặc khi chủ không còn hoạt động → 410. Kế hoạch bị hủy: người nhận vẫn xem được trạng thái "đã hủy" nhưng không trả lời được.
- URL link = `APP_WEB_BASE_URL` + `/plan-invite/<token>`.

### 3.5 Báo sai địa điểm (C)

`POST /places/{placeId}/reports {reason, description?, fileId?}`: gửi được cả khi chưa đăng nhập, có giới hạn tần suất (429). Không tự sửa dữ liệu công khai; admin xử lý ở §4.7.

### 3.6 Hỗ trợ (C)

`POST /support-tickets {category, subject, body}`, `GET /support-tickets[/{id}]`, `POST /support-tickets/{id}/messages`. Thành viên chỉ thấy tin `PUBLIC`. Ticket `CLOSED` không trả lời và không mở lại được.

### 3.7 Tài khoản, thông báo, upload (A)

- `GET/PATCH /me`, đổi email (`/me/email-change` + `/verify`), yêu cầu xóa (`/me/deletion-request`).
- `GET /notifications`, `POST /notifications/{id}/read`. Loại: kế hoạch đổi/hủy, người ấy trả lời lời mời xem kế hoạch, hỗ trợ trả lời/đổi trạng thái, `ADMIN_ANNOUNCEMENT`.
- `POST /uploads` (multipart `file`, `purpose`): PNG/JPEG ≤ 5 MB, lưu riêng tư; muốn hiển thị công khai phải gắn vào địa điểm/bộ sưu tập.
- `POST /events`: sự kiện analytics có mã chống trùng.

## 4. Luồng quản trị (chỉ `SUPER_ADMIN`)

### 4.1 Địa điểm (B)

```
POST /admin/places {…}                 → DRAFT (bản nháp)
PUT  /admin/places/{id} {…}            → chỉ sửa được khi DRAFT hoặc PENDING (PLACE_NOT_EDITABLE)
POST /uploads (purpose=PLACE_IMAGE) → POST /admin/places/{id}/images {fileId, sourceRights, caption?, cover?}
POST /admin/places/{id}/transitions {action, reason?}
```

Vòng đời:

```
DRAFT --SUBMIT--> PENDING --APPROVE--> PUBLISHED --HIDE--------> HIDDEN   --RESTORE--> PENDING
                     |                     └-----DEACTIVATE--> INACTIVE --RESTORE--> PENDING
                     └--RETURN (bắt buộc lý do)--> DRAFT
```

- **SUBMIT/APPROVE chỉ qua khi đủ điều kiện (RULE-20)**: mô tả, loại hình và khu vực còn dùng, ảnh có nguồn/quyền + ảnh bìa, hoạt động chính, trạng thái giá (đã biết hoặc ghi rõ chưa rõ), nguồn + ngày kiểm tra, không trùng chi nhánh. `GET /admin/places/{id}` trả `publishBlockers[]` để giao diện liệt kê.
- **Tự duyệt (RULE-21, OQ-18 đã chốt 21/11/2026):** `SUPER_ADMIN` được duyệt nơi do chính mình tạo, vì chủ dự án vận hành một mình. Vai trò khác (REVIEWER kiêm EDITOR) vẫn bị chặn → 403. Nhật ký vẫn ghi người duyệt.
- HIDE/DEACTIVATE/RETURN bắt buộc lý do. Mọi thao tác ghi `audit_log`.

### 4.2 Nhập CSV (B)

```
GET  /admin/imports/template.csv           → file mẫu (UTF-8)
POST /admin/imports (multipart file)       → batch VALIDATED | INVALID + lỗi từng dòng (chưa ghi gì)
POST /admin/imports/{batchId}/commit       → chỉ khi VALIDATED; tạo toàn bộ thành DRAFT (RULE-23)
```

Tối đa 2 MB, 1000 dòng. Nhãn nhiều mã cách nhau bằng `;`. Giá `KNOWN` phải đủ `standard_for_two`, `min_price`, `max_price`.

### 4.3 Nhãn, khu vực, bộ sưu tập (B)

- `GET/POST /admin/taxonomies`, `PATCH /admin/taxonomies/{id}`, `POST /admin/areas`, `PATCH /admin/areas/{id}`. Không xóa: đổi tên hoặc `DEPRECATED` (RULE-22). Đổi tên thì cập nhật lại tìm kiếm không dấu.
- `GET/POST /admin/collections`, `PUT/DELETE /admin/collections/{id}`: tiêu đề, mô tả, ảnh bìa, khung thời gian hiển thị, danh sách địa điểm có thứ tự.

### 4.4 Tài khoản (A)

| Việc | API |
|---|---|
| Danh sách, tìm, lọc | `GET /admin/users?q=&status=` |
| Tạo | `POST /admin/users` (email trùng → 409 `EMAIL_TAKEN`; có `SUPER_ADMIN` thì kèm link kích hoạt) |
| Chi tiết | `GET /admin/users/{id}` (vai trò, trạng thái, đã cài TOTP chưa, lần đăng nhập cuối) |
| Khóa / mở | `POST .../lock`, `.../unlock` (bắt buộc lý do) |
| Vai trò | `PUT .../roles` |
| Phiên đăng nhập | `GET .../sessions`; đăng xuất mọi thiết bị `POST .../sessions/revoke` |
| Hoạt động | `GET .../activity`: chỉ đếm (danh sách lưu, phiên cùng chọn, kế hoạch, ticket, báo sai) + 50 sự kiện gần nhất. Không trả ghi chú kế hoạch, nội dung tin nhắn hay mô tả báo sai |

### 4.5 Nội dung web và thông báo (B + A)

- `GET/PUT /admin/site-content`: gửi cả object, server chỉ ghi mục thay đổi. Banner bật phải có chữ; link phải `http(s)://` hoặc bắt đầu bằng `/`.
- `POST /admin/notifications {summary ≤ 500, target: ALL | USER, userId?}`: `ALL` gửi mọi tài khoản đang hoạt động. Lịch sử: `GET /admin/notifications/sent`.

### 4.6 Báo cáo và nhật ký (A, số liệu từ B/C)

- `GET /admin/reports/summary?from=&to=&areaId=&categoryId=&platform=`, `GET /admin/reports/export.csv` (cùng bộ lọc).
- Chất lượng dữ liệu do `StatisticsContributor` nộp: B đếm địa điểm đang hiển thị thiếu giá, thiếu giờ, quá hạn xác minh (`PLACES_VERIFICATION_MAX_AGE_DAYS`, mặc định 180, OQ-20); C đếm báo sai đang mở. Lọc theo nền tảng thì các số này "không tính được" thay vì 0.
- `GET /admin/audit-logs?actorId=&objectType=&objectId=&from=&to=`: nhật ký không sửa/xóa được qua API.

### 4.7 Báo sai và hỗ trợ (C)

- `GET /admin/place-reports[/{id}]`, `POST .../transitions {status, note?}`: `NEW → CHECKING → RESOLVED | INSUFFICIENT` (đóng bắt buộc ghi chú); đã đóng chỉ mở lại về `CHECKING`.
- `GET /admin/support-tickets[/{id}]`, `POST .../messages {body, visibility: PUBLIC | INTERNAL}`, `POST .../transitions {status}`. Trả lời `PUBLIC` gửi thông báo cho thành viên và chuyển `NEW → IN_PROGRESS`; `INTERNAL` không bao giờ lộ cho thành viên.

## 5. Thử nhanh bằng Docker Compose

```powershell
cd gowithbae-be/docker
copy .env.example .env      # điền DB_PASSWORD, JWT_SECRET
docker compose up -d --build
docker compose run --rm backend --app.admin.bootstrap-email=<email-của-bạn>
```

Web http://localhost:8081, hộp thư giả (mã OTP) http://localhost:8025. Chi tiết: DEVELOPMENT.md §9–10.

## 6. Còn thiếu / đang chờ quyết định

- Lưu ảnh production (OQ-21): đang lưu trên đĩa container (`gwb-uploads`).
- Ngưỡng quá hạn xác minh (OQ-20), SMTP thật, backup và chính sách giữ dữ liệu: chưa chốt.
