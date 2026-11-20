-- ════════════════════════════════════════════════════════════════════
--  DỮ LIỆU MINH HỌA — CHỈ DÙNG CHO DB DEV/LOCAL. KHÔNG CHẠY TRÊN PRODUCTION (RULE-28).
-- ════════════════════════════════════════════════════════════════════
-- File được sinh bởi scripts/gen_dev_seed.py — KHÔNG sửa tay; sửa danh sách trong script rồi sinh lại.
-- Tên, địa chỉ, giá, giờ đều là dữ liệu giả; mọi mã bắt đầu bằng DEMO để dễ nhận và xóa.
--
-- Gồm: 6 khu vực, 8 loại hình, 35 địa điểm (31 công khai + nháp/chờ duyệt/ẩn/ngừng),
--      giá ở mọi mức lọc và đúng biên (0, 199k, 200k, 399k, 400k, 700k, 700k+1, chưa rõ),
--      giờ thường / qua đêm / chỉ cuối tuần / nghỉ Thứ Hai / chưa rõ, 5 ngày ngoại lệ,
--      7 bộ sưu tập (5 đang hiện, 1 hết hạn, 1 sắp ra mắt).
--
-- Cách nạp (giữ đúng tiếng Việt; KHÔNG pipe qua PowerShell 5.1):
--   docker cp scripts/dev-seed.sql gwb-db:/tmp/dev-seed.sql
--   docker exec gwb-db psql -U gowithbae -d gowithbae -f /tmp/dev-seed.sql
-- Chạy lại được: đầu file xóa dữ liệu DEMO cũ. Nếu đã có saved list / phiên chọn / kế hoạch / báo sai
-- trỏ tới địa điểm DEMO thì phần dọn bên dưới xóa luôn các bản ghi đó trên DB dev.

BEGIN;

-- ───────────── Dọn dữ liệu DEMO cũ ─────────────
CREATE TEMP TABLE demo_place ON COMMIT DROP AS SELECT id FROM place WHERE code LIKE 'DEMO-%';
DELETE FROM collection
WHERE title LIKE '[Minh h%'
   OR cover_file_id IN (SELECT id FROM stored_file WHERE storage_key LIKE 'demo/%')
   OR id IN (SELECT collection_id FROM collection_item WHERE place_id IN (SELECT id FROM demo_place));
DELETE FROM saved_list_item WHERE place_id IN (SELECT id FROM demo_place);
DELETE FROM place_report WHERE place_id IN (SELECT id FROM demo_place);
DELETE FROM date_reminder WHERE plan_id IN (SELECT id FROM date_plan WHERE place_id IN (SELECT id FROM demo_place));
DELETE FROM visit_self_report WHERE plan_id IN (SELECT id FROM date_plan WHERE place_id IN (SELECT id FROM demo_place));
DELETE FROM date_plan WHERE place_id IN (SELECT id FROM demo_place);
DELETE FROM vote WHERE candidate_id IN (SELECT id FROM choice_candidate WHERE place_id IN (SELECT id FROM demo_place));
DELETE FROM choice_candidate WHERE place_id IN (SELECT id FROM demo_place);
DELETE FROM place_image     WHERE place_id IN (SELECT id FROM demo_place);
DELETE FROM place_tag       WHERE place_id IN (SELECT id FROM demo_place);
DELETE FROM place_price     WHERE place_id IN (SELECT id FROM demo_place);
DELETE FROM opening_hours   WHERE place_id IN (SELECT id FROM demo_place);
DELETE FROM date_exception  WHERE place_id IN (SELECT id FROM demo_place);
DELETE FROM place WHERE id IN (SELECT id FROM demo_place);
DELETE FROM stored_file WHERE storage_key LIKE 'demo/%';
DELETE FROM taxonomy WHERE code LIKE 'DEMO_%';
DELETE FROM area WHERE code LIKE 'DEMO_%';

-- ───────────── Khu vực và loại hình (V2 chưa seed, chờ OQ-01) ─────────────
INSERT INTO area (code, name) VALUES
    ('DEMO_Q1', 'Quận 1 (minh họa)'),
    ('DEMO_Q3', 'Quận 3 (minh họa)'),
    ('DEMO_BT', 'Bình Thạnh (minh họa)'),
    ('DEMO_PN', 'Phú Nhuận (minh họa)'),
    ('DEMO_Q7', 'Quận 7 (minh họa)'),
    ('DEMO_TD', 'Thủ Đức (minh họa)');

INSERT INTO taxonomy (group_code, code, name) VALUES
    ('CATEGORY', 'DEMO_CAFE', 'Quán cà phê'),
    ('CATEGORY', 'DEMO_RESTAURANT', 'Nhà hàng'),
    ('CATEGORY', 'DEMO_WORKSHOP', 'Workshop'),
    ('CATEGORY', 'DEMO_BAR', 'Bar / pub'),
    ('CATEGORY', 'DEMO_OUTDOOR', 'Công viên / ngoài trời'),
    ('CATEGORY', 'DEMO_CINEMA', 'Rạp phim'),
    ('CATEGORY', 'DEMO_GALLERY', 'Bảo tàng / phòng tranh'),
    ('CATEGORY', 'DEMO_GAME', 'Khu vui chơi');

-- ───────────── Địa điểm ─────────────
INSERT INTO place (code, name, search_text, category_id, area_id, address, lat, lng, description, phone,
                   reservation_recommended, hours_status, status, source, verified_at, created_by, published_at)
SELECT v.code, v.name, '',
       (SELECT id FROM taxonomy WHERE code = v.category),
       (SELECT id FROM area WHERE code = v.area),
       v.address, v.lat, v.lng, v.description, v.phone, v.reservation, v.hours_status, v.status,
       'Dữ liệu minh họa', current_date - v.days_ago, NULL,
       CASE WHEN v.status IN ('PUBLISHED', 'HIDDEN', 'INACTIVE') THEN now() - make_interval(days => v.days_ago) END
FROM (VALUES
    ('DEMO-001', 'Mèo Lười Cà Phê', 'DEMO_CAFE', 'DEMO_Q1', 'Mèo số 001, đường Minh Họa', 10.7769, 106.7009, 'Quán nhỏ có mèo, góc đọc sách yên tĩnh.', '0280000001', false, 'KNOWN', 'PUBLISHED', 1),
    ('DEMO-002', 'Xưởng Gốm Đất Nung', 'DEMO_WORKSHOP', 'DEMO_Q3', 'Xưởng số 002, đường Minh Họa', 10.7840, 106.6880, 'Workshop làm gốm 2 tiếng cho hai người.', '0280000002', true, 'KNOWN', 'PUBLISHED', 2),
    ('DEMO-003', 'Rooftop Hoàng Hôn', 'DEMO_RESTAURANT', 'DEMO_Q1', 'Rooftop số 003, đường Minh Họa', 10.7730, 106.7040, 'Nhà hàng sân thượng, ngắm hoàng hôn.', '0280000003', true, 'KNOWN', 'PUBLISHED', 3),
    ('DEMO-004', 'Bar Đêm Muộn', 'DEMO_BAR', 'DEMO_BT', 'Bar số 004, đường Minh Họa', 10.8030, 106.7140, 'Mở qua đêm, có band nhạc cuối tuần.', NULL, false, 'KNOWN', 'PUBLISHED', 4),
    ('DEMO-005', 'Công Viên Ven Sông', 'DEMO_OUTDOOR', 'DEMO_BT', 'Công số 005, đường Minh Họa', 10.7990, 106.7210, 'Đi dạo miễn phí ven sông.', NULL, NULL, 'KNOWN', 'PUBLISHED', 5),
    ('DEMO-006', 'Lớp Vẽ Tranh Sơn Dầu', 'DEMO_WORKSHOP', 'DEMO_Q3', 'Lớp số 006, đường Minh Họa', 10.7810, 106.6920, NULL, NULL, NULL, 'UNKNOWN', 'PUBLISHED', 6),
    ('DEMO-007', 'Nhà Hàng Kỷ Niệm', 'DEMO_RESTAURANT', 'DEMO_Q1', 'Nhà số 007, đường Minh Họa', 10.7750, 106.6990, 'Phòng riêng cho dịp kỷ niệm.', '0280000007', true, 'KNOWN', 'PUBLISHED', 7),
    ('DEMO-008', 'Quán Mới Chưa Duyệt', 'DEMO_CAFE', 'DEMO_Q1', 'Quán số 008, đường Minh Họa', 10.7700, 106.7009, 'Bản nháp — không được xuất hiện ở trang công khai.', NULL, NULL, 'UNKNOWN', 'DRAFT', 0),
    ('DEMO-009', 'Cà Phê Sách Thư Viện', 'DEMO_CAFE', 'DEMO_Q1', 'Cà số 009, đường Minh Họa', 10.7780, 106.6979, 'Không gian đọc sách, cấm nói to.', '0280000009', false, 'KNOWN', 'PUBLISHED', 2),
    ('DEMO-010', 'Pub Nhạc Sống Phố Cổ', 'DEMO_BAR', 'DEMO_Q1', 'Pub số 010, đường Minh Họa', 10.7772, 106.7049, 'Band acoustic mỗi tối.', '0280000010', false, 'KNOWN', 'PUBLISHED', 9),
    ('DEMO-011', 'Bảo Tàng Mỹ Thuật Mini', 'DEMO_GALLERY', 'DEMO_Q1', 'Bảo số 011, đường Minh Họa', 10.7795, 106.7021, 'Triển lãm tranh đương đại, vé vào cửa rẻ.', '0280000011', false, 'KNOWN', 'PUBLISHED', 12),
    ('DEMO-012', 'Rạp Phim Độc Lập', 'DEMO_CINEMA', 'DEMO_Q1', 'Rạp số 012, đường Minh Họa', 10.7718, 106.6984, 'Chiếu phim nghệ thuật, ghế đôi.', '0280000012', true, 'KNOWN', 'PUBLISHED', 3),
    ('DEMO-013', 'Tiệm Bánh Tự Làm', 'DEMO_WORKSHOP', 'DEMO_Q1', 'Tiệm số 013, đường Minh Họa', 10.7811, 106.6998, 'Tự làm bánh kem 90 phút.', '0280000013', true, 'KNOWN', 'PUBLISHED', 15),
    ('DEMO-014', 'Cà Phê Sân Vườn Cũ', 'DEMO_CAFE', 'DEMO_Q3', 'Cà số 014, đường Minh Họa', 10.7855, 106.6850, 'Biệt thự cũ, nhiều cây xanh.', '0280000014', false, 'KNOWN', 'PUBLISHED', 4),
    ('DEMO-015', 'Bistro Ánh Nến', 'DEMO_RESTAURANT', 'DEMO_Q3', 'Bistro số 015, đường Minh Họa', 10.7815, 106.6900, 'Món Âu, ánh nến, nhạc jazz nhẹ.', '0280000015', true, 'KNOWN', 'PUBLISHED', 8),
    ('DEMO-016', 'Board Game Café', 'DEMO_GAME', 'DEMO_Q3', 'Board số 016, đường Minh Họa', 10.7870, 106.6905, 'Hơn 300 trò board game.', '0280000016', false, 'KNOWN', 'PUBLISHED', 6),
    ('DEMO-017', 'Studio Nến Thơm', 'DEMO_WORKSHOP', 'DEMO_Q3', 'Studio số 017, đường Minh Họa', 10.7792, 106.6842, 'Tự pha mùi và đổ nến, mang về.', '0280000017', true, 'KNOWN', 'PUBLISHED', 10),
    ('DEMO-018', 'Cà Phê Chó Corgi', 'DEMO_CAFE', 'DEMO_BT', 'Cà số 018, đường Minh Họa', 10.8080, 106.7100, 'Chơi cùng 6 bé corgi.', '0280000018', false, 'KNOWN', 'PUBLISHED', 2),
    ('DEMO-019', 'Thuyền Ngắm Sông Đêm', 'DEMO_OUTDOOR', 'DEMO_BT', 'Thuyền số 019, đường Minh Họa', 10.7970, 106.7200, 'Du thuyền 60 phút trên sông.', '0280000019', true, 'KNOWN', 'PUBLISHED', 5),
    ('DEMO-020', 'Quán Nướng Sân Thượng', 'DEMO_RESTAURANT', 'DEMO_BT', 'Quán số 020, đường Minh Họa', 10.8065, 106.7165, 'Nướng tại bàn, view thành phố.', '0280000020', false, 'KNOWN', 'PUBLISHED', 11),
    ('DEMO-021', 'Khu Trò Chơi Điện Tử', 'DEMO_GAME', 'DEMO_BT', 'Khu số 021, đường Minh Họa', 10.8025, 106.7105, 'Máy gắp thú, bowling, bi-a.', NULL, false, 'KNOWN', 'PUBLISHED', 20),
    ('DEMO-022', 'Trà Chiều Kiểu Anh', 'DEMO_CAFE', 'DEMO_PN', 'Trà số 022, đường Minh Họa', 10.8000, 106.6815, 'Bánh ngọt và trà, đồ sứ cổ.', '0280000022', true, 'KNOWN', 'PUBLISHED', 3),
    ('DEMO-023', 'Bếp Chay An Nhiên', 'DEMO_RESTAURANT', 'DEMO_PN', 'Bếp số 023, đường Minh Họa', 10.7970, 106.6790, 'Ẩm thực chay, không gian thiền.', '0280000023', false, 'KNOWN', 'PUBLISHED', 7),
    ('DEMO-024', 'Phòng Tranh Ánh Sáng', 'DEMO_GALLERY', 'DEMO_PN', 'Phòng số 024, đường Minh Họa', 10.8022, 106.6772, 'Triển lãm ánh sáng tương tác.', '0280000024', false, 'KNOWN', 'PUBLISHED', 14),
    ('DEMO-025', 'Hồ Bán Nguyệt Đi Dạo', 'DEMO_OUTDOOR', 'DEMO_Q7', 'Hồ số 025, đường Minh Họa', 10.7350, 106.7215, 'Đi dạo quanh hồ, cầu đi bộ.', NULL, NULL, 'KNOWN', 'PUBLISHED', 1),
    ('DEMO-026', 'Nhà Hàng Ven Hồ', 'DEMO_RESTAURANT', 'DEMO_Q7', 'Nhà số 026, đường Minh Họa', 10.7358, 106.7230, 'Hải sản, bàn sát mặt hồ.', '0280000026', true, 'KNOWN', 'PUBLISHED', 6),
    ('DEMO-027', 'Sân Trượt Patin', 'DEMO_GAME', 'DEMO_Q7', 'Sân số 027, đường Minh Họa', 10.7310, 106.7200, 'Thuê giày, nhạc sôi động.', '0280000027', false, 'KNOWN', 'PUBLISHED', 9),
    ('DEMO-028', 'Rạp Phim Ghế Nằm', 'DEMO_CINEMA', 'DEMO_Q7', 'Rạp số 028, đường Minh Họa', 10.7385, 106.7175, 'Ghế nằm, chăn, phục vụ tại ghế.', '0280000028', true, 'KNOWN', 'PUBLISHED', 4),
    ('DEMO-029', 'Vườn Dâu Tự Hái', 'DEMO_OUTDOOR', 'DEMO_TD', 'Vườn số 029, đường Minh Họa', 10.8540, 106.7750, 'Tự hái dâu, picnic bãi cỏ.', '0280000029', true, 'KNOWN', 'PUBLISHED', 8),
    ('DEMO-030', 'Cà Phê Võng Ven Kênh', 'DEMO_CAFE', 'DEMO_TD', 'Cà số 030, đường Minh Họa', 10.8470, 106.7720, 'Nằm võng, gió kênh, giá sinh viên.', NULL, false, 'KNOWN', 'PUBLISHED', 2),
    ('DEMO-031', 'Xưởng Làm Đồ Da', 'DEMO_WORKSHOP', 'DEMO_TD', 'Xưởng số 031, đường Minh Họa', 10.8512, 106.7660, 'Tự làm ví da khắc tên.', '0280000031', true, 'KNOWN', 'PUBLISHED', 13),
    ('DEMO-032', 'Beer Garden Sân Cỏ', 'DEMO_BAR', 'DEMO_TD', 'Beer số 032, đường Minh Họa', 10.8450, 106.7670, 'Bia tươi, sân cỏ, chiếu bóng đá.', '0280000032', false, 'KNOWN', 'PUBLISHED', 5),
    ('DEMO-033', 'Quán Chờ Duyệt', 'DEMO_CAFE', 'DEMO_PN', 'Quán số 033, đường Minh Họa', 10.8045, 106.6845, 'Đã gửi duyệt, chờ người duyệt.', '0280000033', false, 'KNOWN', 'PENDING', 1),
    ('DEMO-034', 'Quán Tạm Ẩn Sửa Chữa', 'DEMO_RESTAURANT', 'DEMO_Q3', 'Quán số 034, đường Minh Họa', 10.7890, 106.6930, 'Đang sửa chữa, tạm ẩn.', '0280000034', false, 'KNOWN', 'HIDDEN', 30),
    ('DEMO-035', 'Quán Đã Ngừng Hoạt Động', 'DEMO_BAR', 'DEMO_Q1', 'Quán số 035, đường Minh Họa', 10.7690, 106.7079, 'Đã đóng cửa vĩnh viễn.', NULL, false, 'KNOWN', 'INACTIVE', 60)
) AS v(code, name, category, area, address, lat, lng, description, phone, reservation, hours_status, status, days_ago);

-- ───────────── Giá chuẩn cho hai người (biên RULE-03) ─────────────
INSERT INTO place_price (place_id, status, standard_for_two, min_price, max_price, includes, source, verified_at)
SELECT p.id, v.status, v.std::bigint, v.min::bigint, v.max::bigint, v.includes, 'Dữ liệu minh họa', current_date
FROM (VALUES
    ('DEMO-001', 'KNOWN', 150000, 100000, 200000, '2 đồ uống'),
    ('DEMO-002', 'KNOWN', 400000, 400000, 600000, 'Phí workshop 2 người'),
    ('DEMO-003', 'KNOWN', 700000, 500000, 1000000, 'Bữa tối 2 người'),
    ('DEMO-004', 'KNOWN', 250000, 200000, 400000, '2 cocktail'),
    ('DEMO-005', 'KNOWN', 0, 0, 0, 'Miễn phí'),
    ('DEMO-006', 'UNKNOWN', NULL, NULL, NULL, NULL),
    ('DEMO-007', 'KNOWN', 1200000, 900000, 1800000, 'Set kỷ niệm 2 người'),
    ('DEMO-009', 'KNOWN', 120000, 90000, 160000, '2 đồ uống'),
    ('DEMO-010', 'KNOWN', 399000, 300000, 600000, '2 bia thủ công'),
    ('DEMO-011', 'KNOWN', 60000, 60000, 60000, '2 vé vào cửa'),
    ('DEMO-012', 'KNOWN', 200000, 180000, 260000, '2 vé + bắp nước'),
    ('DEMO-013', 'KNOWN', 550000, 450000, 650000, 'Nguyên liệu + hướng dẫn'),
    ('DEMO-014', 'KNOWN', 199000, 150000, 250000, '2 đồ uống'),
    ('DEMO-015', 'KNOWN', 850000, 700000, 1100000, '2 phần ăn chính + rượu'),
    ('DEMO-016', 'KNOWN', 140000, 100000, 180000, 'Phí chơi + 2 nước'),
    ('DEMO-017', 'KNOWN', 350000, 300000, 420000, '2 hũ nến'),
    ('DEMO-018', 'KNOWN', 180000, 150000, 220000, '2 đồ uống'),
    ('DEMO-019', 'KNOWN', 700001, 650000, 900000, 'Vé 2 người + nước'),
    ('DEMO-020', 'KNOWN', 450000, 350000, 600000, 'Set nướng 2 người'),
    ('DEMO-021', 'UNKNOWN', NULL, NULL, NULL, NULL),
    ('DEMO-022', 'KNOWN', 380000, 320000, 450000, 'Set trà chiều 2 người'),
    ('DEMO-023', 'KNOWN', 260000, 200000, 320000, '2 phần ăn'),
    ('DEMO-024', 'KNOWN', 300000, 300000, 300000, '2 vé'),
    ('DEMO-025', 'KNOWN', 0, 0, 0, 'Miễn phí'),
    ('DEMO-026', 'KNOWN', 950000, 800000, 1300000, 'Set hải sản 2 người'),
    ('DEMO-027', 'KNOWN', 220000, 200000, 260000, '2 vé + thuê giày'),
    ('DEMO-028', 'KNOWN', 500000, 450000, 600000, '2 vé ghế nằm + combo'),
    ('DEMO-029', 'KNOWN', 160000, 120000, 200000, '2 vé + 500g dâu'),
    ('DEMO-030', 'KNOWN', 60000, 40000, 80000, '2 đồ uống'),
    ('DEMO-031', 'KNOWN', 620000, 500000, 750000, '2 sản phẩm da'),
    ('DEMO-032', 'KNOWN', 300000, 250000, 400000, '2 bia + đồ nhắm'),
    ('DEMO-033', 'KNOWN', 100000, 80000, 120000, '2 đồ uống'),
    ('DEMO-034', 'KNOWN', 300000, 250000, 350000, '2 phần ăn'),
    ('DEMO-035', 'KNOWN', 250000, 200000, 300000, '2 đồ uống')
) AS v(code, status, std, min, max, includes)
JOIN place p ON p.code = v.code;

-- ───────────── Giờ mở cửa (1 = Thứ Hai … 7 = Chủ Nhật; closes ≤ opens = qua đêm) ─────────────
INSERT INTO opening_hours (place_id, day_of_week, opens_at, closes_at)
SELECT p.id, v.dow, v.opens, v.closes
FROM (VALUES
    ('DEMO-001', 1, TIME '07:00', TIME '22:00'),
    ('DEMO-001', 2, TIME '07:00', TIME '22:00'),
    ('DEMO-001', 3, TIME '07:00', TIME '22:00'),
    ('DEMO-001', 4, TIME '07:00', TIME '22:00'),
    ('DEMO-001', 5, TIME '07:00', TIME '22:00'),
    ('DEMO-001', 6, TIME '07:00', TIME '22:00'),
    ('DEMO-001', 7, TIME '07:00', TIME '22:00'),
    ('DEMO-002', 2, TIME '09:00', TIME '18:00'),
    ('DEMO-002', 3, TIME '09:00', TIME '18:00'),
    ('DEMO-002', 4, TIME '09:00', TIME '18:00'),
    ('DEMO-002', 5, TIME '09:00', TIME '18:00'),
    ('DEMO-002', 6, TIME '09:00', TIME '18:00'),
    ('DEMO-002', 7, TIME '09:00', TIME '18:00'),
    ('DEMO-003', 1, TIME '16:00', TIME '23:30'),
    ('DEMO-003', 2, TIME '16:00', TIME '23:30'),
    ('DEMO-003', 3, TIME '16:00', TIME '23:30'),
    ('DEMO-003', 4, TIME '16:00', TIME '23:30'),
    ('DEMO-003', 5, TIME '16:00', TIME '23:30'),
    ('DEMO-003', 6, TIME '16:00', TIME '23:30'),
    ('DEMO-003', 7, TIME '16:00', TIME '23:30'),
    ('DEMO-004', 1, TIME '18:00', TIME '02:00'),
    ('DEMO-004', 2, TIME '18:00', TIME '02:00'),
    ('DEMO-004', 3, TIME '18:00', TIME '02:00'),
    ('DEMO-004', 4, TIME '18:00', TIME '02:00'),
    ('DEMO-004', 5, TIME '18:00', TIME '02:00'),
    ('DEMO-004', 6, TIME '18:00', TIME '02:00'),
    ('DEMO-004', 7, TIME '18:00', TIME '02:00'),
    ('DEMO-005', 1, TIME '05:00', TIME '21:00'),
    ('DEMO-005', 2, TIME '05:00', TIME '21:00'),
    ('DEMO-005', 3, TIME '05:00', TIME '21:00'),
    ('DEMO-005', 4, TIME '05:00', TIME '21:00'),
    ('DEMO-005', 5, TIME '05:00', TIME '21:00'),
    ('DEMO-005', 6, TIME '05:00', TIME '21:00'),
    ('DEMO-005', 7, TIME '05:00', TIME '21:00'),
    ('DEMO-007', 1, TIME '11:00', TIME '14:00'),
    ('DEMO-007', 2, TIME '11:00', TIME '14:00'),
    ('DEMO-007', 3, TIME '11:00', TIME '14:00'),
    ('DEMO-007', 4, TIME '11:00', TIME '14:00'),
    ('DEMO-007', 5, TIME '11:00', TIME '14:00'),
    ('DEMO-007', 6, TIME '11:00', TIME '14:00'),
    ('DEMO-007', 7, TIME '11:00', TIME '14:00'),
    ('DEMO-007', 1, TIME '17:30', TIME '22:00'),
    ('DEMO-007', 2, TIME '17:30', TIME '22:00'),
    ('DEMO-007', 3, TIME '17:30', TIME '22:00'),
    ('DEMO-007', 4, TIME '17:30', TIME '22:00'),
    ('DEMO-007', 5, TIME '17:30', TIME '22:00'),
    ('DEMO-007', 6, TIME '17:30', TIME '22:00'),
    ('DEMO-007', 7, TIME '17:30', TIME '22:00'),
    ('DEMO-009', 1, TIME '06:00', TIME '21:00'),
    ('DEMO-009', 2, TIME '06:00', TIME '21:00'),
    ('DEMO-009', 3, TIME '06:00', TIME '21:00'),
    ('DEMO-009', 4, TIME '06:00', TIME '21:00'),
    ('DEMO-009', 5, TIME '06:00', TIME '21:00'),
    ('DEMO-009', 6, TIME '06:00', TIME '21:00'),
    ('DEMO-009', 7, TIME '06:00', TIME '21:00'),
    ('DEMO-010', 1, TIME '19:00', TIME '00:00'),
    ('DEMO-010', 2, TIME '19:00', TIME '00:00'),
    ('DEMO-010', 3, TIME '19:00', TIME '00:00'),
    ('DEMO-010', 4, TIME '19:00', TIME '00:00'),
    ('DEMO-010', 5, TIME '19:00', TIME '03:00'),
    ('DEMO-010', 6, TIME '19:00', TIME '03:00'),
    ('DEMO-010', 7, TIME '19:00', TIME '03:00'),
    ('DEMO-011', 2, TIME '08:30', TIME '17:00'),
    ('DEMO-011', 3, TIME '08:30', TIME '17:00'),
    ('DEMO-011', 4, TIME '08:30', TIME '17:00'),
    ('DEMO-011', 5, TIME '08:30', TIME '17:00'),
    ('DEMO-011', 6, TIME '08:30', TIME '17:00'),
    ('DEMO-011', 7, TIME '08:30', TIME '17:00'),
    ('DEMO-012', 1, TIME '09:00', TIME '00:00'),
    ('DEMO-012', 2, TIME '09:00', TIME '00:00'),
    ('DEMO-012', 3, TIME '09:00', TIME '00:00'),
    ('DEMO-012', 4, TIME '09:00', TIME '00:00'),
    ('DEMO-012', 5, TIME '09:00', TIME '00:00'),
    ('DEMO-012', 6, TIME '09:00', TIME '00:00'),
    ('DEMO-012', 7, TIME '09:00', TIME '00:00'),
    ('DEMO-013', 2, TIME '09:00', TIME '18:00'),
    ('DEMO-013', 3, TIME '09:00', TIME '18:00'),
    ('DEMO-013', 4, TIME '09:00', TIME '18:00'),
    ('DEMO-013', 5, TIME '09:00', TIME '18:00'),
    ('DEMO-013', 6, TIME '09:00', TIME '18:00'),
    ('DEMO-013', 7, TIME '09:00', TIME '18:00'),
    ('DEMO-014', 1, TIME '07:00', TIME '22:00'),
    ('DEMO-014', 2, TIME '07:00', TIME '22:00'),
    ('DEMO-014', 3, TIME '07:00', TIME '22:00'),
    ('DEMO-014', 4, TIME '07:00', TIME '22:00'),
    ('DEMO-014', 5, TIME '07:00', TIME '22:00'),
    ('DEMO-014', 6, TIME '07:00', TIME '22:00'),
    ('DEMO-014', 7, TIME '07:00', TIME '22:00'),
    ('DEMO-015', 1, TIME '11:00', TIME '14:00'),
    ('DEMO-015', 2, TIME '11:00', TIME '14:00'),
    ('DEMO-015', 3, TIME '11:00', TIME '14:00'),
    ('DEMO-015', 4, TIME '11:00', TIME '14:00'),
    ('DEMO-015', 5, TIME '11:00', TIME '14:00'),
    ('DEMO-015', 6, TIME '11:00', TIME '14:00'),
    ('DEMO-015', 7, TIME '11:00', TIME '14:00'),
    ('DEMO-015', 1, TIME '17:30', TIME '22:00'),
    ('DEMO-015', 2, TIME '17:30', TIME '22:00'),
    ('DEMO-015', 3, TIME '17:30', TIME '22:00'),
    ('DEMO-015', 4, TIME '17:30', TIME '22:00'),
    ('DEMO-015', 5, TIME '17:30', TIME '22:00'),
    ('DEMO-015', 6, TIME '17:30', TIME '22:00'),
    ('DEMO-015', 7, TIME '17:30', TIME '22:00'),
    ('DEMO-016', 1, TIME '08:00', TIME '22:00'),
    ('DEMO-016', 2, TIME '08:00', TIME '22:00'),
    ('DEMO-016', 3, TIME '08:00', TIME '22:00'),
    ('DEMO-016', 4, TIME '08:00', TIME '22:00'),
    ('DEMO-016', 5, TIME '08:00', TIME '22:00'),
    ('DEMO-016', 6, TIME '08:00', TIME '22:00'),
    ('DEMO-016', 7, TIME '08:00', TIME '22:00'),
    ('DEMO-017', 6, TIME '09:00', TIME '21:00'),
    ('DEMO-017', 7, TIME '09:00', TIME '21:00'),
    ('DEMO-018', 1, TIME '08:00', TIME '22:00'),
    ('DEMO-018', 2, TIME '08:00', TIME '22:00'),
    ('DEMO-018', 3, TIME '08:00', TIME '22:00'),
    ('DEMO-018', 4, TIME '08:00', TIME '22:00'),
    ('DEMO-018', 5, TIME '08:00', TIME '22:00'),
    ('DEMO-018', 6, TIME '08:00', TIME '22:00'),
    ('DEMO-018', 7, TIME '08:00', TIME '22:00'),
    ('DEMO-019', 1, TIME '16:00', TIME '23:30'),
    ('DEMO-019', 2, TIME '16:00', TIME '23:30'),
    ('DEMO-019', 3, TIME '16:00', TIME '23:30'),
    ('DEMO-019', 4, TIME '16:00', TIME '23:30'),
    ('DEMO-019', 5, TIME '16:00', TIME '23:30'),
    ('DEMO-019', 6, TIME '16:00', TIME '23:30'),
    ('DEMO-019', 7, TIME '16:00', TIME '23:30'),
    ('DEMO-020', 1, TIME '16:00', TIME '23:30'),
    ('DEMO-020', 2, TIME '16:00', TIME '23:30'),
    ('DEMO-020', 3, TIME '16:00', TIME '23:30'),
    ('DEMO-020', 4, TIME '16:00', TIME '23:30'),
    ('DEMO-020', 5, TIME '16:00', TIME '23:30'),
    ('DEMO-020', 6, TIME '16:00', TIME '23:30'),
    ('DEMO-020', 7, TIME '16:00', TIME '23:30'),
    ('DEMO-021', 1, TIME '08:00', TIME '22:00'),
    ('DEMO-021', 2, TIME '08:00', TIME '22:00'),
    ('DEMO-021', 3, TIME '08:00', TIME '22:00'),
    ('DEMO-021', 4, TIME '08:00', TIME '22:00'),
    ('DEMO-021', 5, TIME '08:00', TIME '22:00'),
    ('DEMO-021', 6, TIME '08:00', TIME '22:00'),
    ('DEMO-021', 7, TIME '08:00', TIME '22:00'),
    ('DEMO-022', 2, TIME '09:00', TIME '18:00'),
    ('DEMO-022', 3, TIME '09:00', TIME '18:00'),
    ('DEMO-022', 4, TIME '09:00', TIME '18:00'),
    ('DEMO-022', 5, TIME '09:00', TIME '18:00'),
    ('DEMO-022', 6, TIME '09:00', TIME '18:00'),
    ('DEMO-022', 7, TIME '09:00', TIME '18:00'),
    ('DEMO-023', 1, TIME '11:00', TIME '14:00'),
    ('DEMO-023', 2, TIME '11:00', TIME '14:00'),
    ('DEMO-023', 3, TIME '11:00', TIME '14:00'),
    ('DEMO-023', 4, TIME '11:00', TIME '14:00'),
    ('DEMO-023', 5, TIME '11:00', TIME '14:00'),
    ('DEMO-023', 6, TIME '11:00', TIME '14:00'),
    ('DEMO-023', 7, TIME '11:00', TIME '14:00'),
    ('DEMO-023', 1, TIME '17:30', TIME '22:00'),
    ('DEMO-023', 2, TIME '17:30', TIME '22:00'),
    ('DEMO-023', 3, TIME '17:30', TIME '22:00'),
    ('DEMO-023', 4, TIME '17:30', TIME '22:00'),
    ('DEMO-023', 5, TIME '17:30', TIME '22:00'),
    ('DEMO-023', 6, TIME '17:30', TIME '22:00'),
    ('DEMO-023', 7, TIME '17:30', TIME '22:00'),
    ('DEMO-024', 6, TIME '09:00', TIME '21:00'),
    ('DEMO-024', 7, TIME '09:00', TIME '21:00'),
    ('DEMO-025', 1, TIME '05:00', TIME '21:00'),
    ('DEMO-025', 2, TIME '05:00', TIME '21:00'),
    ('DEMO-025', 3, TIME '05:00', TIME '21:00'),
    ('DEMO-025', 4, TIME '05:00', TIME '21:00'),
    ('DEMO-025', 5, TIME '05:00', TIME '21:00'),
    ('DEMO-025', 6, TIME '05:00', TIME '21:00'),
    ('DEMO-025', 7, TIME '05:00', TIME '21:00'),
    ('DEMO-026', 1, TIME '11:00', TIME '14:00'),
    ('DEMO-026', 2, TIME '11:00', TIME '14:00'),
    ('DEMO-026', 3, TIME '11:00', TIME '14:00'),
    ('DEMO-026', 4, TIME '11:00', TIME '14:00'),
    ('DEMO-026', 5, TIME '11:00', TIME '14:00'),
    ('DEMO-026', 6, TIME '11:00', TIME '14:00'),
    ('DEMO-026', 7, TIME '11:00', TIME '14:00'),
    ('DEMO-026', 1, TIME '17:30', TIME '22:00'),
    ('DEMO-026', 2, TIME '17:30', TIME '22:00'),
    ('DEMO-026', 3, TIME '17:30', TIME '22:00'),
    ('DEMO-026', 4, TIME '17:30', TIME '22:00'),
    ('DEMO-026', 5, TIME '17:30', TIME '22:00'),
    ('DEMO-026', 6, TIME '17:30', TIME '22:00'),
    ('DEMO-026', 7, TIME '17:30', TIME '22:00'),
    ('DEMO-027', 1, TIME '08:00', TIME '22:00'),
    ('DEMO-027', 2, TIME '08:00', TIME '22:00'),
    ('DEMO-027', 3, TIME '08:00', TIME '22:00'),
    ('DEMO-027', 4, TIME '08:00', TIME '22:00'),
    ('DEMO-027', 5, TIME '08:00', TIME '22:00'),
    ('DEMO-027', 6, TIME '08:00', TIME '22:00'),
    ('DEMO-027', 7, TIME '08:00', TIME '22:00'),
    ('DEMO-028', 1, TIME '09:00', TIME '00:00'),
    ('DEMO-028', 2, TIME '09:00', TIME '00:00'),
    ('DEMO-028', 3, TIME '09:00', TIME '00:00'),
    ('DEMO-028', 4, TIME '09:00', TIME '00:00'),
    ('DEMO-028', 5, TIME '09:00', TIME '00:00'),
    ('DEMO-028', 6, TIME '09:00', TIME '00:00'),
    ('DEMO-028', 7, TIME '09:00', TIME '00:00'),
    ('DEMO-029', 6, TIME '09:00', TIME '21:00'),
    ('DEMO-029', 7, TIME '09:00', TIME '21:00'),
    ('DEMO-030', 1, TIME '06:00', TIME '21:00'),
    ('DEMO-030', 2, TIME '06:00', TIME '21:00'),
    ('DEMO-030', 3, TIME '06:00', TIME '21:00'),
    ('DEMO-030', 4, TIME '06:00', TIME '21:00'),
    ('DEMO-030', 5, TIME '06:00', TIME '21:00'),
    ('DEMO-030', 6, TIME '06:00', TIME '21:00'),
    ('DEMO-030', 7, TIME '06:00', TIME '21:00'),
    ('DEMO-031', 2, TIME '09:00', TIME '18:00'),
    ('DEMO-031', 3, TIME '09:00', TIME '18:00'),
    ('DEMO-031', 4, TIME '09:00', TIME '18:00'),
    ('DEMO-031', 5, TIME '09:00', TIME '18:00'),
    ('DEMO-031', 6, TIME '09:00', TIME '18:00'),
    ('DEMO-031', 7, TIME '09:00', TIME '18:00'),
    ('DEMO-032', 1, TIME '18:00', TIME '02:00'),
    ('DEMO-032', 2, TIME '18:00', TIME '02:00'),
    ('DEMO-032', 3, TIME '18:00', TIME '02:00'),
    ('DEMO-032', 4, TIME '18:00', TIME '02:00'),
    ('DEMO-032', 5, TIME '18:00', TIME '02:00'),
    ('DEMO-032', 6, TIME '18:00', TIME '02:00'),
    ('DEMO-032', 7, TIME '18:00', TIME '02:00'),
    ('DEMO-033', 1, TIME '07:00', TIME '22:00'),
    ('DEMO-033', 2, TIME '07:00', TIME '22:00'),
    ('DEMO-033', 3, TIME '07:00', TIME '22:00'),
    ('DEMO-033', 4, TIME '07:00', TIME '22:00'),
    ('DEMO-033', 5, TIME '07:00', TIME '22:00'),
    ('DEMO-033', 6, TIME '07:00', TIME '22:00'),
    ('DEMO-033', 7, TIME '07:00', TIME '22:00'),
    ('DEMO-034', 1, TIME '11:00', TIME '14:00'),
    ('DEMO-034', 2, TIME '11:00', TIME '14:00'),
    ('DEMO-034', 3, TIME '11:00', TIME '14:00'),
    ('DEMO-034', 4, TIME '11:00', TIME '14:00'),
    ('DEMO-034', 5, TIME '11:00', TIME '14:00'),
    ('DEMO-034', 6, TIME '11:00', TIME '14:00'),
    ('DEMO-034', 7, TIME '11:00', TIME '14:00'),
    ('DEMO-034', 1, TIME '17:30', TIME '22:00'),
    ('DEMO-034', 2, TIME '17:30', TIME '22:00'),
    ('DEMO-034', 3, TIME '17:30', TIME '22:00'),
    ('DEMO-034', 4, TIME '17:30', TIME '22:00'),
    ('DEMO-034', 5, TIME '17:30', TIME '22:00'),
    ('DEMO-034', 6, TIME '17:30', TIME '22:00'),
    ('DEMO-034', 7, TIME '17:30', TIME '22:00'),
    ('DEMO-035', 1, TIME '18:00', TIME '02:00'),
    ('DEMO-035', 2, TIME '18:00', TIME '02:00'),
    ('DEMO-035', 3, TIME '18:00', TIME '02:00'),
    ('DEMO-035', 4, TIME '18:00', TIME '02:00'),
    ('DEMO-035', 5, TIME '18:00', TIME '02:00'),
    ('DEMO-035', 6, TIME '18:00', TIME '02:00'),
    ('DEMO-035', 7, TIME '18:00', TIME '02:00')
) AS v(code, dow, opens, closes)
JOIN place p ON p.code = v.code;

-- Ngày ngoại lệ, tính từ hôm nay
INSERT INTO date_exception (place_id, on_date, closed, opens_at, closes_at, note)
SELECT p.id, current_date + v.days, v.closed, v.opens, v.closes, v.note
FROM (VALUES
    ('DEMO-003', 7, true, NULL::time, NULL::time, 'Nghỉ bảo trì (minh họa)'),
    ('DEMO-001', 1, false, TIME '10:00', TIME '20:00', 'Mở muộn, đóng sớm (minh họa)'),
    ('DEMO-004', 2, true, NULL::time, NULL::time, 'Nghỉ sự kiện riêng (minh họa)'),
    ('DEMO-015', 3, false, TIME '17:30', TIME '23:30', 'Mở thêm giờ dịp lễ (minh họa)'),
    ('DEMO-025', 5, true, NULL::time, NULL::time, 'Đóng cửa vệ sinh hồ (minh họa)')
) AS v(code, days, closed, opens, closes, note)
JOIN place p ON p.code = v.code;

-- ───────────── Nhãn ─────────────
INSERT INTO place_tag (place_id, taxonomy_id)
SELECT p.id, t.id
FROM (VALUES
    ('DEMO-001', 'CAFE'), ('DEMO-001', 'CATS'), ('DEMO-001', 'COZY'), ('DEMO-001', 'QUIET'), ('DEMO-001', 'FIRST_DATE'), ('DEMO-002', 'POTTERY'),
    ('DEMO-002', 'ARTSY'), ('DEMO-002', 'QUIET'), ('DEMO-002', 'WEEKEND'), ('DEMO-003', 'DINING'), ('DEMO-003', 'ROMANTIC'), ('DEMO-003', 'ROOFTOP'),
    ('DEMO-003', 'SUNSET'), ('DEMO-003', 'ANNIVERSARY'), ('DEMO-004', 'GAMES'), ('DEMO-004', 'LIVELY'), ('DEMO-004', 'LIVE_BAND'), ('DEMO-005', 'SPORTS'),
    ('DEMO-005', 'RIVERSIDE'), ('DEMO-005', 'OUTDOOR'), ('DEMO-005', 'PET_FRIENDLY'), ('DEMO-006', 'PAINTING'), ('DEMO-006', 'VINTAGE'), ('DEMO-007', 'DINING'),
    ('DEMO-007', 'PRIVATE'), ('DEMO-007', 'ROMANTIC'), ('DEMO-007', 'ANNIVERSARY'), ('DEMO-007', 'NON_SMOKING'), ('DEMO-008', 'CAFE'), ('DEMO-009', 'CAFE'),
    ('DEMO-009', 'QUIET'), ('DEMO-009', 'MINIMAL'), ('DEMO-009', 'NON_SMOKING'), ('DEMO-009', 'FIRST_DATE'), ('DEMO-010', 'GAMES'), ('DEMO-010', 'LIVELY'),
    ('DEMO-010', 'LIVE_BAND'), ('DEMO-010', 'BIRTHDAY'), ('DEMO-011', 'EXHIBITION'), ('DEMO-011', 'ARTSY'), ('DEMO-011', 'QUIET'), ('DEMO-011', 'WHEELCHAIR'),
    ('DEMO-012', 'MOVIE'), ('DEMO-012', 'PRIVATE'), ('DEMO-012', 'ROMANTIC'), ('DEMO-012', 'FIRST_DATE'), ('DEMO-013', 'BAKING'), ('DEMO-013', 'COZY'),
    ('DEMO-013', 'MODERN'), ('DEMO-013', 'BIRTHDAY'), ('DEMO-014', 'CAFE'), ('DEMO-014', 'GARDEN'), ('DEMO-014', 'GREENERY'), ('DEMO-014', 'VINTAGE'),
    ('DEMO-014', 'QUIET'), ('DEMO-014', 'PET_FRIENDLY'), ('DEMO-015', 'DINING'), ('DEMO-015', 'ROMANTIC'), ('DEMO-015', 'PRIVATE'), ('DEMO-015', 'ANNIVERSARY'),
    ('DEMO-016', 'GAMES'), ('DEMO-016', 'LIVELY'), ('DEMO-016', 'COZY'), ('DEMO-016', 'WEEKEND'), ('DEMO-017', 'ARTSY'), ('DEMO-017', 'COZY'),
    ('DEMO-017', 'MINIMAL'), ('DEMO-017', 'FIRST_DATE'), ('DEMO-018', 'CAFE'), ('DEMO-018', 'DOGS'), ('DEMO-018', 'PET_FRIENDLY'), ('DEMO-018', 'LIVELY'),
    ('DEMO-019', 'RIVERSIDE'), ('DEMO-019', 'SUNSET'), ('DEMO-019', 'ROMANTIC'), ('DEMO-019', 'ANNIVERSARY'), ('DEMO-019', 'OUTDOOR'), ('DEMO-020', 'DINING'),
    ('DEMO-020', 'ROOFTOP'), ('DEMO-020', 'LIVELY'), ('DEMO-020', 'BIRTHDAY'), ('DEMO-021', 'GAMES'), ('DEMO-021', 'SPORTS'), ('DEMO-021', 'LIVELY'),
    ('DEMO-022', 'CAFE'), ('DEMO-022', 'VINTAGE'), ('DEMO-022', 'COZY'), ('DEMO-022', 'QUIET'), ('DEMO-022', 'ANNIVERSARY'), ('DEMO-023', 'DINING'),
    ('DEMO-023', 'QUIET'), ('DEMO-023', 'GARDEN'), ('DEMO-023', 'NON_SMOKING'), ('DEMO-023', 'WHEELCHAIR'), ('DEMO-024', 'EXHIBITION'), ('DEMO-024', 'MODERN'),
    ('DEMO-024', 'ARTSY'), ('DEMO-024', 'WEEKEND'), ('DEMO-025', 'SPORTS'), ('DEMO-025', 'LAKESIDE'), ('DEMO-025', 'GREENERY'), ('DEMO-025', 'SUNSET'),
    ('DEMO-025', 'OUTDOOR'), ('DEMO-025', 'PET_FRIENDLY'), ('DEMO-026', 'DINING'), ('DEMO-026', 'LAKESIDE'), ('DEMO-026', 'ROMANTIC'), ('DEMO-026', 'ANNIVERSARY'),
    ('DEMO-027', 'SPORTS'), ('DEMO-027', 'LIVELY'), ('DEMO-027', 'GAMES'), ('DEMO-027', 'WEEKEND'), ('DEMO-028', 'MOVIE'), ('DEMO-028', 'PRIVATE'),
    ('DEMO-028', 'COZY'), ('DEMO-028', 'ANNIVERSARY'), ('DEMO-029', 'GREENERY'), ('DEMO-029', 'OUTDOOR'), ('DEMO-029', 'PET_FRIENDLY'), ('DEMO-029', 'WEEKEND'),
    ('DEMO-030', 'CAFE'), ('DEMO-030', 'RIVERSIDE'), ('DEMO-030', 'GREENERY'), ('DEMO-030', 'QUIET'), ('DEMO-031', 'ARTSY'), ('DEMO-031', 'VINTAGE'),
    ('DEMO-031', 'QUIET'), ('DEMO-031', 'BIRTHDAY'), ('DEMO-032', 'LIVELY'), ('DEMO-032', 'OUTDOOR'), ('DEMO-032', 'LIVE_BAND'), ('DEMO-032', 'DOGS'),
    ('DEMO-033', 'CAFE'), ('DEMO-033', 'COZY'), ('DEMO-034', 'DINING'), ('DEMO-034', 'QUIET'), ('DEMO-035', 'LIVELY')
) AS v(code, tag)
JOIN place p ON p.code = v.code
JOIN taxonomy t ON t.code = v.tag AND t.group_code <> 'CATEGORY';

-- ───────────── Ảnh (tệp giả; cần FILES_PUBLIC_BASE_URL trỏ tới nơi có ảnh thì FE mới hiện) ─────────────
INSERT INTO stored_file (purpose, content_type, size_bytes, storage_key)
SELECT 'PLACE_IMAGE', 'image/jpeg', 1, 'demo/' || lower(code) || '.jpg' FROM place WHERE code LIKE 'DEMO-%';

INSERT INTO place_image (place_id, file_id, sort_order, is_cover, caption, source_rights)
SELECT p.id, f.id, 0, true, p.name, 'Ảnh minh họa, không dùng cho production'
FROM place p JOIN stored_file f ON f.storage_key = 'demo/' || lower(p.code) || '.jpg';

-- ───────────── Bộ sưu tập ─────────────
WITH c AS (
    INSERT INTO collection (title, body, visible_from, visible_until)
    VALUES ('[Minh họa] Hẹn đầu nhẹ nhàng', 'Những nơi yên tĩnh, dễ trò chuyện cho buổi hẹn đầu.', now() - interval '1 day', NULL)
    RETURNING id
)
INSERT INTO collection_item (collection_id, place_id, sort_order)
SELECT c.id, p.id, v.ord
FROM c, (VALUES ('DEMO-001', 0), ('DEMO-009', 1), ('DEMO-012', 2), ('DEMO-017', 3), ('DEMO-014', 4), ('DEMO-008', 5)) AS v(code, ord)
JOIN place p ON p.code = v.code;

WITH c AS (
    INSERT INTO collection (title, body, visible_from, visible_until)
    VALUES ('[Minh họa] Dịp kỷ niệm', 'Không gian riêng tư, lãng mạn.', NULL, NULL)
    RETURNING id
)
INSERT INTO collection_item (collection_id, place_id, sort_order)
SELECT c.id, p.id, v.ord
FROM c, (VALUES ('DEMO-003', 0), ('DEMO-007', 1), ('DEMO-015', 2), ('DEMO-019', 3), ('DEMO-026', 4), ('DEMO-028', 5)) AS v(code, ord)
JOIN place p ON p.code = v.code;

WITH c AS (
    INSERT INTO collection (title, body, visible_from, visible_until)
    VALUES ('[Minh họa] Cuối tuần ngoài trời', 'Đi dạo, picnic, vận động nhẹ.', NULL, NULL)
    RETURNING id
)
INSERT INTO collection_item (collection_id, place_id, sort_order)
SELECT c.id, p.id, v.ord
FROM c, (VALUES ('DEMO-005', 0), ('DEMO-025', 1), ('DEMO-029', 2), ('DEMO-027', 3), ('DEMO-030', 4)) AS v(code, ord)
JOIN place p ON p.code = v.code;

WITH c AS (
    INSERT INTO collection (title, body, visible_from, visible_until)
    VALUES ('[Minh họa] Hẹn hò dưới 200k', 'Vui mà không tốn kém.', NULL, NULL)
    RETURNING id
)
INSERT INTO collection_item (collection_id, place_id, sort_order)
SELECT c.id, p.id, v.ord
FROM c, (VALUES ('DEMO-005', 0), ('DEMO-025', 1), ('DEMO-030', 2), ('DEMO-011', 3), ('DEMO-009', 4), ('DEMO-016', 5), ('DEMO-018', 6), ('DEMO-014', 7)) AS v(code, ord)
JOIN place p ON p.code = v.code;

WITH c AS (
    INSERT INTO collection (title, body, visible_from, visible_until)
    VALUES ('[Minh họa] Cú đêm', 'Mở muộn, có nơi qua đêm.', NULL, NULL)
    RETURNING id
)
INSERT INTO collection_item (collection_id, place_id, sort_order)
SELECT c.id, p.id, v.ord
FROM c, (VALUES ('DEMO-004', 0), ('DEMO-010', 1), ('DEMO-032', 2), ('DEMO-012', 3), ('DEMO-035', 4)) AS v(code, ord)
JOIN place p ON p.code = v.code;

WITH c AS (
    INSERT INTO collection (title, body, visible_from, visible_until)
    VALUES ('[Minh họa] Bộ sưu tập đã hết hạn', 'Không được hiện (visible_until đã qua).', now() - interval '30 days', now() - interval '1 day')
    RETURNING id
)
INSERT INTO collection_item (collection_id, place_id, sort_order)
SELECT c.id, p.id, v.ord
FROM c, (VALUES ('DEMO-001', 0)) AS v(code, ord)
JOIN place p ON p.code = v.code;

WITH c AS (
    INSERT INTO collection (title, body, visible_from, visible_until)
    VALUES ('[Minh họa] Bộ sưu tập sắp ra mắt', 'Không được hiện (visible_from ở tương lai).', now() + interval '7 days', NULL)
    RETURNING id
)
INSERT INTO collection_item (collection_id, place_id, sort_order)
SELECT c.id, p.id, v.ord
FROM c, (VALUES ('DEMO-003', 0)) AS v(code, ord)
JOIN place p ON p.code = v.code;

-- ───────────── Chuỗi tìm kiếm không dấu ─────────────
UPDATE place p SET search_text = lower(translate(
    concat_ws(' ', p.name, a.name,
              (SELECT string_agg(t.name, ' ' ORDER BY t.group_code, t.code)
               FROM place_tag pt JOIN taxonomy t ON t.id = pt.taxonomy_id
               WHERE pt.place_id = p.id AND t.status = 'ACTIVE')),
    'àáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđÀÁẢÃẠĂẰẮẲẴẶÂẦẤẨẪẬÈÉẺẼẸÊỀẾỂỄỆÌÍỈĨỊÒÓỎÕỌÔỒỐỔỖỘƠỜỚỞỠỢÙÚỦŨỤƯỪỨỬỮỰỲÝỶỸỴĐ',
    'aaaaaaaaaaaaaaaaaeeeeeeeeeeeiiiiiooooooooooooooooouuuuuuuuuuuyyyyydaaaaaaaaaaaaaaaaaeeeeeeeeeeeiiiiiooooooooooooooooouuuuuuuuuuuyyyyyd'))
FROM area a
WHERE a.id = p.area_id AND p.code LIKE 'DEMO-%';

COMMIT;
