# gowithbae-be

Backend GoWithBae G1: Spring Boot 4.1 · Java 21 · PostgreSQL (Supabase) · Flyway.

**Yêu cầu sản phẩm:** [SRS hợp nhất G1](docs/SRS.md) — Draft, giữ đầy đủ câu hỏi mở và đề xuất chờ duyệt.

**Triển khai người A:** [docs/MEMBER_A.md](docs/MEMBER_A.md) (quyết định OQ, SMTP/TOTP, interface B/C).

**Hướng dẫn làm việc (Supabase, quy trình endpoint, migration):** [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) · **Tech stack:** [TECHSTACK.md](TECHSTACK.md)

## API contract

`api/openapi.yaml` là **nguồn chuẩn duy nhất** cho FE và BE (OpenAPI 3.0, contract 0.3.0, phủ FR-01..FR-35 của `GWB-REQ-G1` v1.1/CR-001; SRS hợp nhất v1.3).

- `x-status: draft` + `x-oq`: hành vi phụ thuộc câu hỏi mở trong SRS §13. Hỏi PO trước khi implement, không tự chốt chính sách.
- Build sinh code vào `target/generated-sources/openapi` (package `vn.gowithbae.api`):
  - `*ApiController`: route đã map sẵn, **mọi operation chưa implement trả 501**.
  - `*ApiDelegate`: interface cần implement. Tạo `@Service` implement delegate của tag tương ứng (xem `place/PlacesDelegate.java`).
  - `vn.gowithbae.api.model`: DTO.
- Đổi API: sửa `openapi.yaml` → `./mvnw compile` → FE chạy `npm run gen:api`.

## Chạy local

1. `cp .env.example .env`, điền chuỗi kết nối Supabase (Project Settings → Database → JDBC) và `JWT_SECRET` (≥ 32 byte).
2. `./mvnw spring-boot:run` → Flyway tạo schema (`src/main/resources/db/migration`), API tại `http://localhost:8080/api/v1`.

Không có Supabase? `./mvnw spring-boot:test-run` chạy với Postgres trong Docker (Testcontainers).

## Test

`./mvnw test`: cần Docker. Test chạy Flyway trên Postgres 17 thật và gọi API qua toàn bộ security filter chain.

## Cấu trúc

```
api/openapi.yaml               contract
src/main/java/vn/gowithbae/
  config/SecurityConfig.java   JWT HS256 (claim roles, sid); quy tắc URL thô
  common/ApiExceptionHandler   lỗi → application/problem+json
  place/PlacesDelegate.java    GET /places — endpoint mẫu chạy thật
src/main/resources/db/migration
  V1__init_schema.sql          toàn bộ schema §6
  V2__seed_reference_data.sql  vai trò + nhãn lọc §5.1
  V3__enable_rls_lock_supabase_api.sql  khóa Supabase Data API (RLS + revoke anon)
  V4__visit_reminders_and_deduplication.sql  trạng thái nhắc riêng + chống trùng lượt đi + RLS
```

## Đã có / chưa có

Đã có: khung G1, GET /places mẫu và các module A: email OTP/JWT/logout/hồ sơ/đổi email/yêu cầu xóa, danh sách lưu, inbox, upload PNG/JPEG riêng tư, analytics, admin users/audit/report/CSV. Có kiểm tra sid hiện hành và TOTP cho nhân viên. Xem docs/MEMBER_A.md để biết quyết định và cấu hình.

B/C chưa implement trong lần này. PlaceQueryService của B chưa có: thêm nơi mới và detail list có item trả501 rõ ràng. StatisticsContributor và AccountLifecycleParticipant cần B/C nối; không giả thống kê hoặc query chéo bảng. FR-35 trong tag Me vẫn thuộc C. Không có URL download tệp công khai.

SMTP cần cấu hình thật; nhân viên cần provisioning TOTP. Retention/purge production, backup, provider triển khai và dữ liệu Supabase thật chưa được kiểm chứng. Không gọi đây là bản phát hành production.

V4 không tự xóa lượt đi trùng có sẵn. V100/V101 là migration của A; không sửa migration đã phát hành. Phối hợp thứ tự với khoảng V200 của B/V300 của C để tránh bỏ qua version thấp.