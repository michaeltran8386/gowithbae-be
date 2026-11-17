# Tech stack — GoWithBae G1

Phiên bản ghi theo lần dựng khung ngày 16-11-2026. Nguồn chính xác: `pom.xml` (backend), `package.json` + `package-lock.json` (frontend).

## 1. Toàn hệ thống

| Lớp | Công nghệ | Repo |
|---|---|---|
| Giao diện web (khách + quản trị) | React 19 · TypeScript · Vite · Tailwind CSS | `gowithbae-fe` |
| API | Spring Boot 4.1 (Java 21), REST JSON `/api/v1` | `gowithbae-be` |
| Contract | OpenAPI 3.0 — `gowithbae-be/api/openapi.yaml` | `gowithbae-be` |
| Cơ sở dữ liệu | PostgreSQL trên Supabase (chỉ dùng phần database) | `gowithbae-be` (migration) |
| Xác thực | Email OTP + JWT do backend tự phát | `gowithbae-be` |

```
Trình duyệt ──▶ gowithbae-fe (static) ──/api/v1──▶ gowithbae-be ──JDBC (Session pooler)──▶ Supabase Postgres
```

## 2. Backend (repo này)

### 2.1 Runtime và framework

| Thành phần | Phiên bản | Vai trò |
|---|---|---|
| Java | 21 (target), build được trên JDK 21+ | Ngôn ngữ |
| Spring Boot | 4.1.2 | Khung ứng dụng |
| Spring Framework | 7.0.10 | Spring MVC (servlet) |
| Tomcat embedded | 11.0.27 | Web server |
| Jackson | 3.1.7 (`tools.jackson`) | JSON |
| Jakarta Bean Validation | qua `spring-boot-starter-validation` | Validate request theo ràng buộc trong contract |
| Spring Boot Actuator | 4.1.2 | `/actuator/health` |
| Maven Wrapper | `./mvnw` | Build, không cần cài Maven |

### 2.2 Bảo mật

| Thành phần | Phiên bản | Vai trò |
|---|---|---|
| Spring Security | 7.1.2 | Filter chain, phân quyền URL |
| OAuth2 Resource Server + Nimbus JOSE JWT | 10.9.1 | Kiểm tra JWT HS256 |

JWT do backend phát sau khi xác minh OTP email; không dùng nhà cung cấp danh tính ngoài.

### 2.3 Dữ liệu

| Thành phần | Phiên bản | Vai trò |
|---|---|---|
| PostgreSQL (Supabase) | theo project Supabase (test dùng 17) | Lưu trữ |
| PostgreSQL JDBC | 42.7.14 | Driver |
| HikariCP | 7.0.2 | Connection pool |
| Flyway | 12.4.0 | Migration schema (`db/migration`) |
| Spring JDBC `JdbcClient` | 7.0.10 | Truy vấn đọc, SQL viết tay |
| Hibernate ORM / Spring Data JPA | 7.4.13 | Dùng khi cần entity cho thao tác ghi phức tạp; `ddl-auto: validate` |

### 2.4 Contract và sinh code

| Thành phần | Phiên bản | Vai trò |
|---|---|---|
| OpenAPI | 3.0.3 | Đặc tả API |
| openapi-generator-maven-plugin | 7.27.0, generator `spring` | Sinh `*ApiController`, `*ApiDelegate`, DTO vào `target/generated-sources` |

Cấu hình: `delegatePattern`, `useTags`, `useSpringBoot4`, `useJackson3`, `configPackage = vn.gowithbae.api.config`.

### 2.5 Kiểm thử

| Thành phần | Phiên bản | Vai trò |
|---|---|---|
| JUnit Jupiter | 6.0.3 | Test runner |
| Spring Boot Test + MockMvc | 4.1.2 | Gọi API qua toàn bộ filter chain |
| Testcontainers | 2.0.5 + image `postgres:17-alpine` | Postgres thật cho test; cần Docker |

## 3. Supabase — dùng gì, không dùng gì

| Tính năng Supabase | Dùng? | Ghi chú |
|---|---|---|
| Postgres database | **Có** | Kết nối JDBC qua **Session pooler** (port 5432) |
| Dashboard (Table/SQL Editor) | Chỉ đọc / sửa dữ liệu dev | Schema chỉ đổi qua Flyway |
| Backup | Có, ở gói trả phí | Gói free không có backup tự động (NFR-10) |
| Auth | Không | Backend tự làm OTP + JWT |
| Data API (PostgREST / GraphQL) | **Không — phải khóa** | Tắt trên dashboard; migration V3 bật RLS + thu hồi quyền `anon`/`authenticated` |
| Storage | Không (chưa chọn) | Lưu ảnh/tệp: chờ OQ-21/OQ-22 |
| Realtime, Edge Functions | Không | SRS không có chat/realtime |

## 4. Quyết định và lý do

| Quyết định | Lý do |
|---|---|
| Contract-first, sinh code cả hai phía | FE/BE làm song song; sai lệch field là lỗi build thay vì lỗi lúc chạy |
| Mọi logic, phân quyền ở Spring | SRS yêu cầu kiểm tra quyền tại máy chủ cho mọi thao tác (FR-22, NFR-04); một chỗ duy nhất để kiểm tra |
| Supabase chỉ làm Postgres | Tránh hai đường truy cập dữ liệu (Spring + PostgREST) với hai bộ quy tắc quyền |
| Spring Boot 4 thay vì 3 | Initializr không còn cung cấp 3.x; 4.x là dòng đang được hỗ trợ |
| `JdbcClient` cho truy vấn đọc | Tìm kiếm/lọc có nhiều điều kiện động; SQL rõ ràng dễ tối ưu hơn JPQL |
| Enum lưu `text` + `CHECK` | Thêm giá trị không cần `ALTER TYPE`, khớp chuỗi enum trong contract |
| Lỗi theo RFC 9457 (`problem+json`) | Chuẩn có sẵn trong Spring, FE xử lý lỗi một kiểu duy nhất |

## 5. Chưa chọn (chờ quyết định trong SRS §13)

| Hạng mục | Câu hỏi mở |
|---|---|
| Nhà cung cấp email (gửi OTP) | OQ-06, OQ-22 |
| Bản đồ / tính khoảng cách | OQ-05, OQ-22 |
| Lưu ảnh và tệp | OQ-21, OQ-22 |
| Hosting backend/frontend, giám sát, CI/CD | OQ-16, OQ-22 |
| Rate limit (OTP, báo sai, tải tệp) | OQ-06, NFR-06 |

## 6. Nâng cấp phiên bản

- Spring Boot: nâng theo bản patch (4.1.x) thường xuyên; bản minor đọc release notes trước.
- openapi-generator: nâng xong phải `./mvnw clean test` vì code sinh có thể đổi.
- Flyway: không nâng major giữa chừng một đợt phát hành.
- Sau mỗi lần nâng: `./mvnw clean test` phải xanh, và cập nhật bảng phiên bản ở file này.

## 7. Người A — bổ sung ngày 17-11-2026

Spring Boot Mail/JavaMailSender dùng SMTP cấu hình qua môi trường (phiên bản do Boot BOM quản lý). JWT HS256 kiểm tra issuer/sid/expiry/trạng thái và quyền DB; staff có email OTP + TOTP HMAC-SHA1 6 số/30 giây, secret AES-GCM trong staff_mfa. Rate limit PostgreSQL lưu key HMAC. Kho ảnh local riêng tư; chưa dùng Supabase Storage. OpenAPI 0.3.0 thêm staffCode tùy chọn, các giá trị triển khai và interface phụ thuộc ghi tại docs/MEMBER_A.md.
