# Thành viên A — triển khai và bàn giao

Ngày 17-11-2026. Phạm vi theo TEAM_SPLIT.md và DATABASE_TABLES.md; đối chiếu GoWithBae/Requirements v1.1 + UC-004/005/006/017/022/026. Người yêu cầu đã cho phép lựa chọn các phần A chưa rõ; các lựa chọn dưới đây là quyết định triển khai, không tự ghi là khách đã duyệt toàn bộ SRS.

## Phần đã implement

| Nhóm | Operation / kết quả |
|---|---|
| Auth | Gửi/xác minh email OTP, cấp JWT HS256, logout; kiểm tra sid/expiry/trạng thái tài khoản ở mọi bearer request |
| Me | Xem/sửa tên và avatar của mình; đổi email sau mã xác minh mới; nhận yêu cầu xóa và thu hồi phiên/challenge |
| SavedLists | CRUD list; thêm/bỏ nơi idempotent, ownership404; thẻ/điều kiện nơi qua PlaceQueryService của B |
| Notifications | Inbox phân trang + tổng chưa đọc, đánh dấu đọc idempotent; chỉ người nhận truy cập |
| Uploads | PNG/JPEG đã kiểm tra nội dung; kho local riêng tư, metadata; anonymous chỉ PLACE_REPORT |
| Events | Lô 1–50 event, whitelist thuộc tính, định danh từ phiên server, chống trùng và transaction cả lô |
| AdminUsers | SUPER_ADMIN list/lock/unlock/replace roles, lý do bắt buộc, không tự khóa/hạ vai trò hoặc mất admin cuối |
| AdminAudit | Đọc audit theo quyền/bộ lọc, không có endpoint sửa/xóa; audit thay trạng thái/vai trò cùng transaction |
| AdminReports | Thống kê analytics và CSV cùng quyền/bộ lọc; chỉ số chất lượng qua StatisticsContributor của B/C |

Không implement phần B/C: nội dung địa điểm, cùng chọn, kế hoạch, hỗ trợ hoặc FR-35. GET /me/places/{placeId}/visit-count vẫn thuộc C dù nằm trong tag Me. Frontend chỉ sinh lại schema theo thay đổi contract, không xây màn hình mới.

## Quyết định cho các OQ của A

| OQ | Quyết định triển khai |
|---|---|
| OQ-06 | Email OTP 6 số, TTL 5 phút, tối đa 5 lần sai; cooldown gửi lại 60 giây; 5 lần gửi/email/giờ, 30 lần/IP/giờ; 30 lần verify/IP/15 phút và/email+IP/15 phút. Cửa sổ rate-limit cũ hơn2 ngày được dọn mỗi giờ; cửa sổ hỗ trợ tối đa24 giờ. Các giá trị trong AuthPolicy cấu hình qua app.auth.*. Rate limit lưu DB với key HMAC, không phụ thuộc restart/process memory |
| OQ-06 / NFR-05 | JWT issuer gowithbae, subject user UUID, sid, roles, amr, iat/exp; phiên 8 giờ. Mỗi request kiểm tra access_session với DB và lấy quyền hiện tại, không chỉ tin claim roles. Nhân viên phải có email OTP + TOTP; TOTP 6 số/30 giây, lệch tối đa 1 bước, không dùng lại bước đã nhận |
| OQ-14 | Chỉ 4 loại thông báo trong contract; GET không tự đọc; đọc giữ read_at đầu tiên; không tự thêm producer/email/push/marketing |
| OQ-17 | Khóa hoặc nhận yêu cầu xóa chặn toàn bộ phiên tài khoản ngay; mở khóa không khôi phục phiên. Xóa ở đây là DELETION_REQUESTED để xử lý có giám sát, không tuyên bố đã xóa/ẩn danh toàn bộ lịch sử. Chưa tự chạy purge dữ liệu B/C hoặc áp đặt retention pháp lý |
| OQ-18 | Các API A quản trị/audit/report/export chỉ SUPER_ADMIN; không tự khóa hoặc bỏ vai trò hiện có của mình; bảo vệ admin hoạt động cuối cùng kể cả khi yêu cầu xóa. Thay vai trò thật sự sẽ thu hồi phiên/OTP để đăng nhập lại; vai trò không đổi không ghi audit giả |
| OQ-20 | Guest/account tách nhau, không ghép xuyên thiết bị. Phiên phân tích chia khi ngắt >=30 phút. Chấp nhận event tối đa 90 ngày cũ/5 phút tương lai, chưa tính tương lai trước occurred_at. Kỳ mặc định 30 ngày Việt Nam, tối đa 366 ngày; tỷ lệ là phân số, mẫu số0 hoặc cohort chưa đủ thì null+note. Thuộc tính whitelist được ghi trong analytics/README.md |
| OQ-21/22 | SMTP chuẩn cấu hình qua môi trường, không phụ thuộc nhà cung cấp cụ thể; thiếu/lỗi mail trả503, không log hoặc trả OTP. Upload PNG/JPEG tối đa5MiB, tối đa8192px/mỗi chiều và16MP; 20 upload/IP/phút và/tài khoản/phút. Tệp nằm ngoài static resources, không dựng URL công khai. PDF/SVG/tệp khác chưa hỗ trợ |

Contract tăng 0.3.0, bổ sung staffCode tùy chọn trong POST /auth/otp/verify; bắt buộc ở runtime khi tài khoản là nhân viên. Không xóa x-status/x-oq trong YAML: việc lựa chọn triển khai không thay phê duyệt nghiệp vụ của team. Thay đổi này cần review trong PR theo quy tắc phân công.

## Cấu hình email và MFA

Điền .env từ .env.example: DB_*, JWT_SECRET, SMTP_HOST/PORT/USERNAME/PASSWORD/FROM, SMTP_AUTH, SMTP_STARTTLS. Không dùng mailbox test hoặc trả OTP ở response trong runtime. Test thay OtpDelivery bằng hộp thư in-memory riêng, không gọi email thật.

AUTH_MFA_KEY ít nhất32 byte, nên tách khỏi JWT_SECRET và sao lưu an toàn. Secret TOTP chỉ lưu AES-GCM ciphertext. Nếu dev bỏ khóa riêng, dùng khóa dẫn xuất từ JWT_SECRET; đổi khóa đó cần provisioning lại hoặc kế hoạch re-encryption.

Nhân viên phải được provisioning qua terminal tin cậy, không có endpoint tự nhận MFA bằng một phiên email-only:

```powershell
.\mvnw.cmd package
java -jar target/gowithbae-be-0.0.1-SNAPSHOT.jar --server.port=0 --app.mfa.enroll-user=<UUID-nhan-vien>
```

Tài khoản phải tồn tại và đã có vai trò nhân viên do người vận hành có quyền gán. Lệnh đọc Base32 secret bằng prompt không hiển thị; secret tương ứng phải được cài vào ứng dụng Authenticator của đúng người. Không đưa secret vào command line, log, ticket hoặc repository. Provisioning thu hồi các phiên cũ và ghi audit không chứa secret. Lệnh thoát sau khi lưu; không chạy flag này ở server phục vụ bình thường. Không seed sẵn SUPER_ADMIN, mật khẩu, OTP hoặc TOTP dùng chung.

## Interface B/C cần nối

- `AuditService.log(actor,action,objectType,objectId,before,after,reason)` nhận map thay đổi tối thiểu; từ chối các khóa riêng/bí mật lồng nhau. Không gửi ghi chú riêng hoặc bí mật trong reason.
- `NotificationService.notify(recipientId,type,objectType,objectId,summary)` chỉ INSERT vào bảng A; module gọi phải kiểm tra sự kiện/người nhận đúng trước khi gọi.
- `FileService.requireOwned/openOwned` kiểm tra người sở hữu + purpose; openOwned trả stream cần đóng. B/C không nhận userId do client gửi. Chưa có endpoint công khai download hoặc signed URL; file url=null theo contract. Trường hợp tệp ẩn danh và quyền tải theo đối tượng phải được C nối với quy trình báo sai, không mở đọc chỉ bằng đoán fileId.
- B cung cấp bean `PlaceQueryService`: requirePublished(placeId), getSavedPlace(placeId). Khi chưa có bean, thêm nơi mới và đọc detail list không rỗng trả501 rõ ràng; không query bảng B hoặc giả dữ liệu. Các thao tác list còn lại vẫn hoạt động.
- B/C cung cấp `StatisticsContributor` với cùng kỳ/khu vực/loại hình/nền tảng; chỉ trả count biết rõ trên dataset không chồng nhau. Chưa có provider thì metric chất lượng null+note, không giả0.
- C cung cấp `AccountLifecycleParticipant.revokeAccess(userId)` để thu hồi ShareGrant/RecipientSession trong cùng transaction khóa/xóa. Lỗi hook rollback thay đổi tài khoản; A không sửa bảng C. C phải kiểm tra trạng thái chủ tài khoản/grant khi xử lý link, không chỉ tin URL/token cũ.
- `TestJwtFactory(jdbc,jwtEncoder).token(userId,roles...)` dùng trong test: cần fixture app_user trước; helper gán role + tạo access_session thật và claim MFA cho test nhân viên. Không dùng helper này trong ứng dụng hoặc tạo token production.

## Migration và vận hành

- Không sửa V1–V4. A thêm V100 request_rate_limit và V101 staff_mfa; cả hai bật RLS. Lưu ý Flyway mặc định không chạy migration số thấp hơn version đã áp dụng: các nhánh B200/C300 phải phối hợp thứ tự merge/chạy, không tùy tiện bật outOfOrder trên production.
- Kho local `.local-data/uploads` đã gitignore; không mount làm static. Windows cần ACL chỉ tài khoản chạy service đọc/ghi, Linux thư mục700/tệp600. Phải sao lưu tệp và DB theo cùng kế hoạch trước dùng thật.
- Theo dõi kích thước analytics/rate-limit/audit, chọn lịch retention/purge qua vận hành; không xóa audit/lịch sử tùy tiện trong module khác. Đây chưa phải cam kết tải lớn hoặc thời hạn lưu giữ pháp lý.
- Chạy `./mvnw test` với Docker, `./mvnw package` để tạo JAR. Các API A đã implement là kiểm thử bằng MockMvc + Postgres thật, không chỉ mock repository. Test không thay việc thử SMTP/provider/Supabase thật.
