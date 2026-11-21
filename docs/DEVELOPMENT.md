**Cập nhật người A ngày 17-11-2026:** xem [MEMBER_A.md](MEMBER_A.md) cho trạng thái implement, SMTP/MFA và giao tiếp B/C. Các ví dụ khung bên dưới không thay bản trạng thái này.

# Hướng dẫn làm việc — Backend GoWithBae G1

Đọc kèm: [`api/openapi.yaml`](../api/openapi.yaml) (contract 0.3.0), [SRS hợp nhất v1.3](SRS.md) từ `GWB-REQ-G1` v1.1/CR-001, và hướng dẫn frontend `gowithbae-fe/docs/DEVELOPMENT.md`.

## 1. Bức tranh tổng thể

```
gowithbae-fe (React)  ──HTTP /api/v1──▶  gowithbae-be (Spring Boot)  ──JDBC──▶  Supabase Postgres
        ▲                                        ▲
        └──── npm run gen:api ◀── api/openapi.yaml ──▶ ./mvnw compile (sinh controller/DTO)
```

- **Contract-first.** `api/openapi.yaml` là nguồn chuẩn. Không viết controller tay, không đổi URL/field trong code mà không sửa contract.
- **Supabase chỉ là Postgres.** Không dùng Supabase Auth, Storage, Data API (REST/GraphQL), Edge Functions. Toàn bộ xác thực, phân quyền và quy tắc nghiệp vụ nằm trong Spring Boot.
- **Schema chỉ đổi qua Flyway.** Không sửa bảng bằng Table Editor/SQL Editor của Supabase.

## 2. Cài đặt môi trường

| Công cụ | Phiên bản | Ghi chú |
|---|---|---|
| JDK | 21 trở lên | Project build với `release 21` |
| Docker Desktop | bất kỳ bản mới | Cần cho `./mvnw test` và chạy DB local |
| Maven | không cần cài | Dùng `./mvnw` (Windows: `mvnw.cmd`) |

### 2.1 Tạo project Supabase

1. Tạo project tại supabase.com, region **Southeast Asia (Singapore)** cho gần người dùng.
2. Lưu lại mật khẩu database lúc tạo (không xem lại được, chỉ reset).
3. **Project Settings → Database → Connection string → JDBC**, chọn **Session pooler** (port `5432`).
   - Không dùng **Transaction pooler** (port `6543`): Hibernate/Flyway dùng prepared statement và session, sẽ lỗi ngẫu nhiên.
   - Direct connection (`db.<ref>.supabase.co`) chỉ có IPv6 trên gói free; đa số mạng Việt Nam nên dùng Session pooler.
4. Mỗi môi trường một project riêng: `gowithbae-dev`, `gowithbae-staging`, `gowithbae-prod` (NFR-12). Không dùng chung DB giữa dev và production.

### 2.2 Bảo mật Supabase — bắt buộc làm

Các bảng public của dự án đã bật RLS qua V3/V4/V100/V101. Không bỏ RLS hoặc expose bảng mới qua Data API: đường truy cập nghiệp vụ là Spring; vẫn kiểm tra cấu hình dashboard của project.

Hai lớp chặn, làm cả hai:

1. **Migration `V3__enable_rls_lock_supabase_api.sql` (tự chạy):** bật RLS trên mọi bảng, không tạo policy; thu hồi mọi quyền của `anon`/`authenticated` trên schema `public`. Backend kết nối bằng role owner của bảng nên không bị RLS chặn.
2. **Thủ công trên dashboard, mỗi project:** **Project Settings → Data API**, tắt Data API hoặc bỏ `public` khỏi *Exposed schemas*.

Thêm:
- Không đưa `service_role key`, `anon key` hay mật khẩu DB vào frontend, repo hoặc log.
- Kiểm tra mục **Advisors → Security** sau mỗi lần thêm bảng.

### 2.3 File `.env`

```bash
cp .env.example .env
```

| Biến | Ví dụ | Lấy ở đâu |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://aws-0-ap-southeast-1.pooler.supabase.com:5432/postgres?sslmode=require` | Connection string, Session pooler |
| `DB_USERNAME` | `postgres.<project-ref>` | Cùng chỗ trên (có hậu tố project ref) |
| `DB_PASSWORD` | | Mật khẩu lúc tạo project |
| `JWT_SECRET` | | Tự sinh: `openssl rand -base64 48` (tối thiểu 32 byte) |

`.env` đã nằm trong `.gitignore`. Không commit.

### 2.4 Chạy

```bash
./mvnw spring-boot:run
```

- Lần chạy đầu Flyway tạo toàn bộ schema trên Supabase.
- API: `http://localhost:8080/api/v1`. Health: `http://localhost:8080/actuator/health`.
- Thử nhanh: `curl "http://localhost:8080/api/v1/places?q=ca%20phe"`.

Chạy không cần Supabase (Postgres trong Docker, dữ liệu mất khi tắt):

```bash
./mvnw spring-boot:test-run
```

## 3. Cấu trúc code

```
api/openapi.yaml                    contract
src/main/java/vn/gowithbae/
  config/SecurityConfig.java        JWT + quy tắc URL thô
  common/ApiExceptionHandler.java   lỗi → application/problem+json
  place/PlacesDelegate.java         ví dụ implement delegate
  <module>/                         mỗi module một package: savedlist, choice, plan, auth, support, admin...
src/main/resources/
  application.yml
  db/migration/V*__*.sql            Flyway
target/generated-sources/openapi/   CODE SINH — không sửa, không commit
  vn/gowithbae/api/*ApiController   route, map sẵn
  vn/gowithbae/api/*ApiDelegate     interface cần implement
  vn/gowithbae/api/model/*          DTO
```

Mỗi tag trong contract sinh ra một bộ `XxxApi` / `XxxApiController` / `XxxApiDelegate`. Operation nào chưa có `@Service` implement sẽ trả **501 Not Implemented**.

## 4. Quy trình làm một endpoint

Ví dụ: implement `GET /taxonomies` (tag `Discovery`, FR-03).

### Bước 1 — Đọc yêu cầu

- Tìm operation trong `openapi.yaml`, đọc `summary`/`description` và mã FR/RULE.
- Nếu operation có `x-status: draft`, đọc các `x-oq` trong SRS §13. **Chưa có câu trả lời của PO thì không tự chốt chính sách.** Hỏi, hoặc implement phần không phụ thuộc OQ và ghi `shortcut:` cho phần còn lại.

### Bước 2 — Sửa contract (nếu cần)

Cần thêm field, tham số, mã lỗi? Sửa `api/openapi.yaml` trước, rồi:

```bash
./mvnw compile
```

Quy tắc khi sửa contract:
- Đổi tương thích (thêm field tùy chọn, thêm endpoint): làm được ngay.
- Đổi phá vỡ (đổi tên, xóa field, đổi kiểu, thêm field `required` vào request): báo team FE trước, sửa FE cùng lúc.
- Chuỗi trong `{ ... }` có dấu `:` phải để trong nháy đơn, nếu không generator báo lỗi spec.

### Bước 3 — Implement delegate

Tạo `@Service` trong package module, implement đúng interface delegate:

```java
package vn.gowithbae.discovery;

@Service
public class DiscoveryDelegate implements DiscoveryApiDelegate {

    private final JdbcClient jdbc;

    public DiscoveryDelegate(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ResponseEntity<List<Taxonomy>> listTaxonomies(TaxonomyGroup group) {
        var rows = jdbc.sql("""
                SELECT id, group_code, code, name FROM taxonomy
                WHERE status = 'ACTIVE' AND (CAST(:group AS text) IS NULL OR group_code = CAST(:group AS text))
                ORDER BY group_code, code
                """)
                .param("group", group == null ? null : group.getValue())
                .query((rs, i) -> new Taxonomy(
                        rs.getObject("id", UUID.class),
                        TaxonomyGroup.fromValue(rs.getString("group_code")),
                        rs.getString("code"),
                        rs.getString("name"),
                        ActiveStatus.ACTIVE))
                .list();
        return ResponseEntity.ok(rows);
    }
}
```

Một tag chỉ có **một** bean implement delegate. Tag lớn thì delegate gọi xuống các service nhỏ hơn.

Tham số có thể `null` trong SQL phải `CAST(... AS <kiểu>)`; nếu không Postgres báo `could not determine data type of parameter`.

**Truy cập dữ liệu:** đọc dùng `JdbcClient` với SQL rõ ràng (như `PlacesDelegate`). Ghi phức tạp có thể dùng JPA entity; khi đó `ddl-auto: validate` sẽ kiểm tra entity khớp schema lúc khởi động.

### Bước 4 — Quy ước bắt buộc

| Chủ đề | Quy ước | Nguồn |
|---|---|---|
| Phân quyền | Kiểm tra **trong delegate** mỗi request: chủ sở hữu, vai trò, hoặc ShareGrant còn hiệu lực. `SecurityConfig` chỉ chặn thô theo URL | FR-22, NFR-04 |
| Tài nguyên riêng không thuộc quyền | Trả **404**, không 403, để không lộ sự tồn tại | FR-22 |
| Lỗi | Ném `ResponseStatusException(status, detail)` hoặc `ErrorResponseException`. Không tự trả JSON lỗi | contract `Problem` |
| Lỗi nghiệp vụ | 409 + `code` là mã quy tắc (vd. `RULE-06`, `SLOT_TAKEN`) | |
| Endpoint nhận cả bearer và người nhận link | URL đã `permitAll`; delegate **bắt buộc** kiểm tra `Authorization` hoặc header `X-Recipient-Session` | RULE-12 |
| Bấm lặp / đồng thời | Thao tác tạo nhận `Idempotency-Key`; ràng buộc duy nhất ở DB là chốt chặn cuối | NFR-08 |
| Dữ liệu thiếu | `null`, không phải 0 / mở cửa / không có. FE hiển thị "Chưa có thông tin" | RULE-04 |
| Thời gian | `timestamptz` trong DB; logic ngày/giờ theo `Asia/Ho_Chi_Minh` | |
| Tiền | `bigint` VND, không dùng số thực | |
| Log | Không log OTP, token, mật khẩu, ghi chú riêng | NFR-05 |
| Audit | Duyệt, ẩn, đổi giá, khóa, cấp quyền phải ghi `audit_log` cùng transaction | FR-31 |
| Tìm không dấu | Ghi `place.search_text` bằng `PlacesDelegate.normalize(...)` mỗi khi sửa tên/khu vực/nhãn | FR-02 |
| Đường tắt | Chưa làm đủ thì để `// shortcut: <giới hạn>, <khi nào nâng cấp>` | |

Lấy người dùng hiện tại trong delegate:

```java
var jwt = (Jwt) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
UUID userId = UUID.fromString(jwt.getSubject());
```

### Bước 5 — Test

Thêm case vào test tích hợp (mẫu: `src/test/java/vn/gowithbae/PlacesApiTest.java`). Test chạy Flyway trên Postgres 17 thật trong Docker và gọi qua toàn bộ filter chain.

Tối thiểu cho mỗi endpoint:
- trường hợp đúng;
- dữ liệu biên theo SRS §14.2 (giá 0/200k/400k/700k, thiếu giá/giờ, link thu hồi...);
- người khác / vai trò thấp / đổi id → 404 hoặc 403.

```bash
./mvnw test
```

### Bước 6 — Báo FE

Contract đổi thì FE chạy `npm run gen:api`. Ghi rõ trong commit/PR operation nào đã hết 501.

## 5. Database và migration (Flyway)

### 5.1 Quy tắc

- Mỗi thay đổi schema là một file mới: `src/main/resources/db/migration/V<số>__<mô_tả>.sql`, ví dụ `V3__add_place_slug.sql`. Số tăng dần, không nhảy ngược.
- **Không bao giờ sửa file migration đã chạy** trên môi trường dùng chung. Flyway so checksum và sẽ từ chối khởi động. Muốn sửa thì viết migration mới.
- Hai người cùng tạo `V3` trên hai nhánh: người merge sau đổi số file của mình trước khi merge.
- Migration phải chạy được trên DB đang có dữ liệu: thêm cột `NOT NULL` thì có `DEFAULT` hoặc backfill trong cùng file.
- **Bảng mới phải bật RLS** ngay trong migration tạo bảng: `ALTER TABLE <bảng> ENABLE ROW LEVEL SECURITY;`. Không tạo policy. Quên thì `RowLevelSecurityTest` fail.

### 5.2 Quy ước schema

| Mục | Quy ước |
|---|---|
| Khóa chính | `uuid DEFAULT gen_random_uuid()`; riêng `audit_log` dùng `bigint identity` |
| Enum | `text` + `CHECK (... IN (...))`; giá trị trùng enum trong contract |
| Tên | `snake_case`, bảng số ít (`place`, `date_plan`) |
| Index | `ix_<bảng>_<cột>`; unique: `ux_...` |
| Xóa | Không xóa dữ liệu đã được tham chiếu (địa điểm, nhãn); dùng cột `status` (RULE-22) |
| Ràng buộc nghiệp vụ | Đặt trong DB khi được (CHECK, unique partial index), ví dụ `ux_recipient_session_active` chặn hai người nhận cùng lúc |

### 5.3 Làm việc với Supabase

- **Xem dữ liệu:** Table Editor hoặc SQL Editor, chỉ để đọc / sửa dữ liệu dev.
- **Không đổi schema trên dashboard.** Lần chạy sau Flyway không biết thay đổi đó, các môi trường sẽ lệch nhau.
- Lịch sử migration: bảng `flyway_schema_history`.
- **Reset DB dev** (xóa toàn bộ dữ liệu, không hoàn tác được): SQL Editor chạy `DROP SCHEMA public CASCADE; CREATE SCHEMA public; GRANT ALL ON SCHEMA public TO postgres, public;` rồi khởi động lại backend. Chỉ làm trên project dev.
- **Sao lưu:** gói free không có backup tự động. Production cần gói có daily backup và chạy thử khôi phục trước khi phát hành (NFR-10).
- Dữ liệu minh họa phải có nhãn rõ và không được nạp vào production (RULE-28). Không đặt dữ liệu demo trong `db/migration`.

## 6. Xác thực

- Người dùng đăng nhập bằng email OTP (`/auth/otp/*`); backend tự phát JWT HS256.
- Claim: `sub` = `app_user.id`, `roles` = danh sách vai trò (`MEMBER`, `EDITOR`, `REVIEWER`, `SUPPORT`, `SUPER_ADMIN`), `sid` = `access_session.id`.
- Spring map `roles` thành `ROLE_*`; dùng `hasRole("REVIEWER")` hoặc kiểm tra trong delegate.
- **Đã implement A:** phát JWT sau OTP, kiểm tra sid/expiry/trạng thái mỗi request và quyền từ DB. Nhân viên cần email OTP + TOTP; xem MEMBER_A.md. Test dùng TestJwtFactory tạo phiên DB thật, không tự ký token thiếu sid/issuer/amr.
- Người nhận link không có tài khoản: dùng header `X-Recipient-Session`; token chỉ lưu dạng hash trong `recipient_session.token_hash`.

## 7. Lệnh thường dùng

| Việc | Lệnh |
|---|---|
| Sinh code từ contract + compile | `./mvnw compile` |
| Chạy với Supabase | `./mvnw spring-boot:run` |
| Chạy với Postgres Docker | `./mvnw spring-boot:test-run` |
| Test | `./mvnw test` |
| Build jar | `./mvnw package` → `target/gowithbae-be-*.jar` |

## 8. Lỗi hay gặp

| Triệu chứng | Nguyên nhân | Cách xử lý |
|---|---|---|
| `There were issues with the specification` khi build | YAML sai, thường do `:` trong chuỗi không có nháy | Đặt chuỗi trong `'...'` |
| Gọi API trả 501 | Chưa có `@Service` implement delegate của tag | Implement theo Bước 3 |
| Query param enum trả 400 | Mất `EnumConverterConfiguration` | Giữ `configPackage` = `vn.gowithbae.api.config` trong `pom.xml` |
| `Validate failed: Migration checksum mismatch` | Sửa file migration đã chạy | Hoàn tác file, viết migration mới |
| `prepared statement "S_1" already exists` | Đang dùng Transaction pooler (6543) | Đổi sang Session pooler (5432) |
| `app.jwt.secret phải dài tối thiểu 32 byte` | `JWT_SECRET` ngắn hoặc thiếu | Sinh lại bằng `openssl rand -base64 48` |
| Test bị skip | Docker chưa chạy | Mở Docker Desktop |
| `RowLevelSecurityTest` fail "Bảng thiếu ENABLE ROW LEVEL SECURITY" | Bảng mới chưa bật RLS | Thêm `ALTER TABLE ... ENABLE ROW LEVEL SECURITY;` vào migration tạo bảng |
| Migration treo, không báo lỗi | `ALTER`/`LOCK` trên `flyway_schema_history` (Flyway đang giữ lock bảng này) | Không động tới bảng `flyway_schema_history` trong migration |

## 9. Chạy cả hệ thống bằng Docker Compose

File `docker/compose.yaml` dựng 4 container:

| Service (container) | Image | Cổng máy | Việc |
|---|---|---|---|
| `db` (`gwb-db`) | `postgres:17-alpine` | 5432 | Database, dữ liệu trong volume `gwb-dbdata` |
| `mail` (`gwb-mail`) | `axllent/mailpit:v1.27` | 8025 | Hộp thư giả: mọi email OTP xem ở http://localhost:8025 |
| `backend` (`gwb-api`) | build từ repo này | 8090 | API. Ảnh tải lên lưu ở volume `gwb-uploads` |
| `web` (`gwb-web`) | build từ repo frontend | 8081 | nginx phục vụ web, chuyển `/api/` sang `backend:8080` |

### 9.1 Lần đầu

Clone hai repo **cạnh nhau** (`gowithbae-be` và `gowithbae-fe` cùng một thư mục cha), rồi:

```powershell
cd gowithbae-be/docker
copy .env.example .env
```

Mở `docker/.env`, điền `DB_PASSWORD` và `JWT_SECRET` (`openssl rand -base64 48`). Nếu thư mục frontend không tên `gowithbae-fe` thì đặt `FE_DIR`, ví dụ `FE_DIR=../../web`. File `.env` này không commit.

```powershell
docker compose up -d --build
```

- Web: http://localhost:8081, trang quản trị http://localhost:8081/admin.
- Hộp thư giả: http://localhost:8025. API: http://localhost:8090/api/v1.
- Lần chạy đầu Flyway tạo schema trong `gwb-dbdata`.

Dữ liệu demo (chạy một lần sau khi backend đã lên):

```powershell
docker compose exec db psql -U gowithbae -d gowithbae -f /seed/dev-seed.sql
```

### 9.2 Lệnh thường dùng (chạy trong `gowithbae-be/docker`)

| Việc | Lệnh |
|---|---|
| Bật / tắt | `docker compose up -d` / `docker compose down` (giữ dữ liệu) |
| Sau khi pull code mới | `docker compose up -d --build` |
| Chỉ build lại backend | `docker compose up -d --build backend` |
| Xem log | `docker compose logs -f backend` |
| Trạng thái | `docker compose ps` |
| Xóa sạch dữ liệu | `docker compose down -v` (**mất DB và ảnh**) |

Đang chạy 4 container cũ tạo bằng `docker run`: xóa container trước (`docker rm -f gwb-db gwb-mail gwb-api gwb-web`), dữ liệu vẫn còn vì compose dùng lại volume `gwb-dbdata`, `gwb-uploads`. Giữ đúng `DB_PASSWORD` và `JWT_SECRET` cũ trong `docker/.env`, nếu không backend không vào được DB và admin phải cài lại Authenticator.

### 9.3 Biến môi trường

- `APP_WEB_BASE_URL` (gốc web, cho link kích hoạt Authenticator) và `APP_INVITE_BASE_URL` (tiền tố link mời, **phải kèm `/invite`**) được compose dựng từ `WEB_URL`, mặc định `http://localhost:8081`.
- `AUTH_MFA_KEY` (tùy chọn): khóa mã hóa secret TOTP. Để trống thì dẫn xuất từ `JWT_SECRET`, khi đó đổi `JWT_SECRET` sẽ làm mọi admin phải kích hoạt lại. Đặt sau khi đã có admin cũng vậy.
- `PLACES_VERIFICATION_MAX_AGE_DAYS` (tùy chọn, mặc định 180): ngưỡng "quá hạn xác minh" trên bảng điều khiển.

## 10. Trang quản trị

### 10.1 Tạo admin đầu tiên

Trong `gowithbae-be/docker`, chạy backend một lần với email chủ dự án. Lệnh tạo tài khoản (nếu chưa có), gán `SUPER_ADMIN`, in link kích hoạt rồi thoát:

```powershell
docker compose run --rm backend --app.admin.bootstrap-email=owner@example.com
```

1. Mở link `http://localhost:8081/admin/activate/<token>` in ra cuối log (hết hạn sau 24 giờ, dùng 1 lần). Chạy lại lệnh thì có link mới, link cũ mất hiệu lực.
2. Quét mã QR bằng Google Authenticator (hoặc nhập khóa dạng chữ), nhập mã 6 số để xác nhận.
3. Vào `/login`: nhập email → mã OTP trong http://localhost:8025 → mã 6 số **mới** trong Authenticator → vào `/admin`.

Admin sau tạo trong **Tài khoản → Tạo tài khoản**, chọn vai trò Admin: trang hiện link kích hoạt để gửi cho người đó. Mất điện thoại: vào chi tiết tài khoản → **Cấp lại link Authenticator**.

### 10.2 Màn hình

| Nhóm | Màn |
|---|---|
| Tổng quan | Số liệu chính, xuất CSV, địa điểm chờ duyệt, báo sai và yêu cầu hỗ trợ mới |
| Người dùng | Tìm/lọc, tạo tài khoản, khóa/mở (bắt buộc lý do), đổi vai trò, đăng xuất mọi thiết bị; chi tiết: hoạt động, phiên đăng nhập (IP, thiết bị), nhật ký |
| Địa điểm | Danh sách, tạo/sửa (nhãn, giá, giờ, nguồn), ảnh, gửi duyệt/duyệt/ẩn/ngừng/khôi phục; nhập CSV |
| Danh mục | Nhãn và khu vực (thêm, đổi tên, ngừng dùng); bộ sưu tập |
| Nội dung web | Trang chủ, banner, FAQ, điều khoản, quyền riêng tư, liên hệ, chân trang; gửi thông báo |
| Hỗ trợ | Báo sai (đóng phải có ghi chú), yêu cầu hỗ trợ (trả lời công khai / ghi chú nội bộ) |
| Hệ thống | Nhật ký thao tác quản trị |

### 10.3 Lưu ý vận hành

- **Tự duyệt địa điểm:** `SUPER_ADMIN` được duyệt nơi do chính mình tạo (OQ-18 đã chốt). Vai trò khác vẫn theo RULE-21 (không tự duyệt).
- Ảnh địa điểm phục vụ qua `GET /api/v1/files/{id}` và nằm trong volume `gwb-uploads`. Xóa volume là mất ảnh. Production vẫn cần chọn nơi lưu trữ (OQ-21).
- Lịch sử phiên lưu IP và thiết bị; nội dung trang Quyền riêng tư phải nêu điều này.

## CR-001: endpoint, database và nghiệm thu

Nguồn hiện hành: SRS hợp nhất v1.3 (Requirements v1.1), §4/7/13/17. Ba hành vi đã CONF không hỏi lại; actor xác nhận, sửa lượt và nhắc lại sau đổi giờ còn OQ-10/25.

| Endpoint | Ý nghĩa | Trạng thái khung |
|---|---|---|
| `GET /me/places/{placeId}/visit-count` | Tổng YES hợp lệ của đúng tài khoản/chi nhánh; không đếm click/analytics/dismiss | Contract, trả 501 |
| `GET /date-plans/{planId}/reminder` | `reminder`, `shouldShow`, `canRespond` do server kiểm tra | Contract, trả 501 |
| `PUT /date-plans/{planId}/reminder` | `CONFIRM_VISIT` ghi YES và ANSWERED cùng transaction; `DISMISS` chỉ lưu đóng nhắc | Contract, trả 501 |
| `GET /share-grants/{inviteToken}` | `candidates` được xem trước khi nhập tên/join, không cần tài khoản | Contract mở rộng, trả 501 |

- `visit-report.answer=NO` là câu trả lời Không, không thay `DISMISS`. Giữ endpoint cũ tương thích cho nhánh được PO duyệt.
- Danh tính lấy từ server; count chỉ bearer của tài khoản đó. Không mặc định guest được ghi count riêng hoặc đọc của chủ tạo; reminder delegate phải kiểm tra actor/grant trước đọc và ghi.
- V4 thêm unique `(plan_id, respondent_user_id)` / `(plan_id, respondent_recipient_id)` theo điều kiện NOT NULL. Nếu dữ liệu cũ trùng thì dừng migration; không tự xóa/gộp câu trả lời. Sao lưu và thống nhất cách xử lý trước khi áp dụng lên DB có dữ liệu.
- `date_reminder` có RLS, một hàng/plan/người nhận; trạng thái nhắc, plan và visit_answer là ba trục riêng. Không tự tạo lại nhắc sau đổi giờ hoặc chọn actor trước khi OQ-10/25 chốt.
- Không cần bảng đếm riêng ở giai đoạn khung: truy vấn COUNT DISTINCT lượt hợp lệ theo tài khoản + place; lỗi đọc phải là lỗi, không giả 0.
- Khi implement xác nhận: transaction kiểm tra lại quyền/plan/thời điểm/nhắc, upsert câu trả lời duy nhất, đóng nhắc; retry không tạo lượt mới. Side effect analytics lỗi không biến kết quả đã lưu thành chưa lưu.
- Nghiệm thu thêm AC-053–058: hai plan YES cùng nơi = 2, retry vẫn 1 cho một plan; nhắc tại scheduled_at không chờ PAST; dismiss tải lại vẫn đóng; guest xem trước không đăng nhập nhưng lưu yêu thích cần đăng nhập. `VisitSchemaTest` hiện chỉ kiểm tra ràng buộc DB, chưa chứng minh API đã triển khai.
