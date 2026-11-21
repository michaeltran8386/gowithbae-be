-- Nội dung trang tĩnh do quản trị viên sửa (trang chủ, banner, FAQ, điều khoản, quyền riêng tư, liên hệ, footer).
-- Mỗi mục một dòng jsonb; API dựng thành object SiteContent. Giá trị ban đầu là văn mẫu, quản trị viên thay sau.
CREATE TABLE site_content (
    key         text PRIMARY KEY CHECK (key IN ('homeHero','homeBanner','faq','terms','privacy','contact','footer')),
    value       jsonb NOT NULL,
    updated_by  uuid REFERENCES app_user(id),
    updated_at  timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE site_content ENABLE ROW LEVEL SECURITY;

INSERT INTO site_content (key, value) VALUES
    ('homeHero', '{"title": "Tìm nơi hẹn hò", "subtitle": "Lọc theo khu vực, ngân sách cho hai người và việc hai bạn muốn làm."}'),
    ('homeBanner', '{"enabled": false, "text": "", "linkUrl": null}'),
    ('faq', '[{"question": "GoWithBae có mất phí không?", "answer": "Không. Bạn tìm địa điểm, lưu danh sách và mời người ấy cùng chọn hoàn toàn miễn phí."}, {"question": "Giá trên GoWithBae là giá gì?", "answer": "Là giá tham khảo cho hai người do đội ngũ tổng hợp; giá thực tế có thể thay đổi theo thời điểm."}]'),
    ('terms', '{"body": "Điều khoản sử dụng đang được cập nhật."}'),
    ('privacy', '{"body": "Chính sách quyền riêng tư đang được cập nhật.\n\nGoWithBae lưu email đăng nhập, lịch sử phiên đăng nhập (thời điểm, địa chỉ IP, thiết bị) và các thao tác trong ứng dụng để vận hành và bảo vệ tài khoản."}'),
    ('contact', '{"email": null, "phone": null, "address": null, "facebook": null}'),
    ('footer', '{"text": "© GoWithBae"}');
