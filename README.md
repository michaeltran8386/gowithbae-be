# gowithbae-be

Backend GoWithBae G1: Spring Boot 4.1 · Java 21 · PostgreSQL (Supabase) · Flyway.

**Yêu cầu sản phẩm:** [SRS hợp nhất G1](docs/SRS.md) — Draft, giữ đầy đủ câu hỏi mở và đề xuất chờ duyệt.

**Hướng dẫn làm việc (Supabase, quy trình endpoint, migration):** [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) · **Tech stack:** [TECHSTACK.md](TECHSTACK.md)

## API contract

`api/openapi.yaml` là **nguồn chuẩn duy nhất** cho FE và BE (OpenAPI 3.0, contract 0.2.0, phủ FR-01..FR-35 của `GWB-REQ-G1` v1.1/CR-001; SRS hợp nhất v1.2).

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

Đã có: schema khung, route cho mọi endpoint, xác thực JWT, `GET /places` (từ khóa không dấu, khu vực, ngân sách, sắp xếp mặc định/giá). CR-001 bổ sung contract số lần đã đi/lời nhắc và preview các địa điểm trước join; các operation mới vẫn trả 501 cho tới khi implement delegate.

Chưa có: OTP/phát JWT, kiểm tra thu hồi phiên `sid`, lọc nhãn/giờ/vị trí, các endpoint còn lại, email/bản đồ/lưu tệp, rate limit.

V4 không tự xóa lượt đi trùng có sẵn. Nếu migration báo unique conflict, kiểm tra dữ liệu và chính sách OQ-10/25 trước khi xử lý. Không sửa V1–V3 đã phát hành.
