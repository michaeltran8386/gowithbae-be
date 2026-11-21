# gowithbae-be

Backend GoWithBae G1: Spring Boot 4.1 · Java 21 · PostgreSQL (Supabase) · Flyway.

**Yêu cầu sản phẩm:** [SRS hợp nhất G1](docs/SRS.md) — Draft, giữ đầy đủ câu hỏi mở và đề xuất chờ duyệt.

**Triển khai người A:** [docs/MEMBER_A.md](docs/MEMBER_A.md) (quyết định OQ, SMTP/TOTP, interface B/C).

**Luồng chức năng (đọc đầu tiên):** [docs/FLOWS.md](docs/FLOWS.md) — hệ thống làm được gì, mỗi luồng gọi API nào, quy tắc nào chặn ở server.

**Hướng dẫn làm việc (Supabase, Docker, trang quản trị, quy trình endpoint, migration):** [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) · **Tech stack:** [TECHSTACK.md](TECHSTACK.md)

## API contract

`api/openapi.yaml` là **nguồn chuẩn duy nhất** cho FE và BE (OpenAPI 3.0, contract 0.5.0, phủ FR-01..FR-35 của `GWB-REQ-G1` v1.1/CR-001; SRS hợp nhất v1.3).

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

Chạy cả hệ thống (DB, hộp thư giả, API, web): `cd docker && copy .env.example .env` (điền secret) → `docker compose up -d --build`. Chi tiết và cách tạo admin đầu tiên: [DEVELOPMENT.md §9–10](docs/DEVELOPMENT.md#9-chạy-cả-hệ-thống-bằng-docker-compose).

## Test

`./mvnw test`: cần Docker. Test chạy Flyway trên Postgres 17 thật và gọi API qua toàn bộ security filter chain.

## Cấu trúc

```
api/openapi.yaml                 contract (nguồn chuẩn cho FE và BE)
src/main/java/vn/gowithbae/
  config/ common/ platform/      security, lỗi problem+json, AuditService/NotificationService/FileService
  auth/ me/ savedlist/ notification/ upload/ analytics/ adminaccount/   phần A
  place/ discovery/ content/ admin/{place,imports,taxonomy,collection}/ phần B
  choice/ plan/ support/ report/                                        phần C
src/main/resources/db/migration  V1–V4, V100–V101, V300, V400–V404 (xem docs/DATABASE.md)
docker/compose.yaml              chạy cả hệ thống (db, mail, backend, web)
docs/FLOWS.md                    luồng chức năng
docs/DATABASE.md                 cấu trúc database (41 bảng), sơ đồ quan hệ
docs/DEVELOPMENT.md              cài đặt, Docker, quản trị, quy trình làm endpoint
```

## Đã có / chưa có

Đã có (chi tiết từng luồng trong [docs/FLOWS.md](docs/FLOWS.md)):

| Nhóm | Chức năng |
|---|---|
| Tài khoản (A) | Email OTP, JWT theo phiên, đăng xuất, hồ sơ, đổi email, yêu cầu xóa; admin đăng nhập thêm mã Authenticator, cài qua link dùng một lần |
| Khám phá (B) | Tìm không dấu + lọc khu vực/ngân sách/nhãn, chi tiết, so sánh ≤ 3, bộ sưu tập, ảnh công khai `/files/{id}`, nội dung web |
| Thành viên (A, C) | Danh sách lưu, cùng chọn qua link mời, kế hoạch hẹn + `.ics` + nhắc "Đi chưa?" + link mời người ấy xem và trả lời, báo sai địa điểm, hỗ trợ, thông báo |
| Quản trị | Địa điểm (nháp → duyệt → hiển thị, ảnh, điều kiện xuất bản), nhập CSV, nhãn/khu vực, bộ sưu tập, tài khoản (tạo, khóa/mở, vai trò, phiên, hoạt động), nội dung web, gửi thông báo, báo sai, ticket, báo cáo + CSV, nhật ký |

Migration: V1–V4 nền, V100/V101 (A), V300 (C, đã chạy trên Supabase nhóm — **không sửa**), V400–V404. Migration mới phải đánh số > V404. Cấu trúc đầy đủ: [docs/DATABASE.md](docs/DATABASE.md).

Chưa có / đang chờ: lưu ảnh production (OQ-21, hiện lưu trên đĩa); ngưỡng quá hạn xác minh (OQ-20); SMTP thật, backup, chính sách giữ dữ liệu. Không gọi đây là bản phát hành production.
