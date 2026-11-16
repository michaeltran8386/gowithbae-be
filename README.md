# gowithbae-be

Backend GoWithBae G1: Spring Boot 4.1 · Java 21 · PostgreSQL (Supabase) · Flyway.

## API contract

`api/openapi.yaml` là **nguồn chuẩn duy nhất** cho FE và BE (OpenAPI 3.0, phủ FR-01..FR-34 của `GWB-REQ-G1` v1.0).

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
```

## Đã có / chưa có

Đã có: schema đầy đủ, route cho mọi endpoint, xác thực JWT, `GET /places` (từ khóa không dấu, khu vực, ngân sách, sắp xếp mặc định/giá).

Chưa có: OTP/phát JWT, kiểm tra thu hồi phiên `sid`, lọc nhãn/giờ/vị trí, các endpoint còn lại, email/bản đồ/lưu tệp, rate limit.
