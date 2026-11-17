-- ════════════════════════════════════════════════════════════════════
--  DỮ LIỆU MINH HỌA — CHỈ DÙNG CHO DB DEV/LOCAL. KHÔNG CHẠY TRÊN PRODUCTION (RULE-28).
-- ════════════════════════════════════════════════════════════════════
-- Mục đích: frontend gọi API thật mà không cần tự nhập dữ liệu qua màn quản trị.
-- Tên, địa chỉ, giá, giờ đều là dữ liệu giả; mọi mã bắt đầu bằng DEMO để dễ nhận và xóa.
--
-- Chạy:   psql "<connection-string>" -f scripts/dev-seed.sql
--   hoặc  dán vào Supabase SQL Editor của project DEV.
-- Chạy lại được: đầu file xóa dữ liệu DEMO cũ. Nếu đã có saved list / phiên chọn / kế hoạch
-- trỏ tới địa điểm DEMO thì phần xóa sẽ báo lỗi khóa ngoại — xóa các bản ghi đó trước.

BEGIN;

-- ───────────── Dọn dữ liệu DEMO cũ ─────────────
DELETE FROM collection WHERE title LIKE '[Minh họa]%';
DELETE FROM collection_item WHERE place_id IN (SELECT id FROM place WHERE code LIKE 'DEMO-%');
DELETE FROM place_image     WHERE place_id IN (SELECT id FROM place WHERE code LIKE 'DEMO-%');
DELETE FROM place_tag       WHERE place_id IN (SELECT id FROM place WHERE code LIKE 'DEMO-%');
DELETE FROM place_price     WHERE place_id IN (SELECT id FROM place WHERE code LIKE 'DEMO-%');
DELETE FROM opening_hours   WHERE place_id IN (SELECT id FROM place WHERE code LIKE 'DEMO-%');
DELETE FROM date_exception  WHERE place_id IN (SELECT id FROM place WHERE code LIKE 'DEMO-%');
DELETE FROM place WHERE code LIKE 'DEMO-%';
DELETE FROM stored_file WHERE storage_key LIKE 'demo/%';
DELETE FROM taxonomy WHERE code LIKE 'DEMO_%';
DELETE FROM area WHERE code LIKE 'DEMO_%';

-- ───────────── Khu vực và loại hình (V2 chưa seed, chờ OQ-01) ─────────────
INSERT INTO area (code, name) VALUES
    ('DEMO_Q1', 'Quận 1 (minh họa)'),
    ('DEMO_Q3', 'Quận 3 (minh họa)'),
    ('DEMO_BT', 'Bình Thạnh (minh họa)');

INSERT INTO taxonomy (group_code, code, name) VALUES
    ('CATEGORY', 'DEMO_CAFE', 'Quán cà phê'),
    ('CATEGORY', 'DEMO_RESTAURANT', 'Nhà hàng'),
    ('CATEGORY', 'DEMO_WORKSHOP', 'Workshop'),
    ('CATEGORY', 'DEMO_BAR', 'Bar'),
    ('CATEGORY', 'DEMO_OUTDOOR', 'Công viên / ngoài trời');

-- ───────────── Địa điểm ─────────────
-- search_text = tên + khu vực + nhãn, bỏ dấu, chữ thường (cùng quy tắc PlacesDelegate.normalize).
INSERT INTO place (code, name, search_text, category_id, area_id, address, lat, lng, description, phone,
                   reservation_recommended, hours_status, status, source, verified_at, published_at)
SELECT v.code, v.name, v.search_text,
       (SELECT id FROM taxonomy WHERE code = v.category),
       (SELECT id FROM area WHERE code = v.area),
       v.address, v.lat, v.lng, v.description, v.phone, v.reservation, v.hours_status, v.status,
       'Dữ liệu minh họa', current_date - v.days_ago, CASE WHEN v.status = 'PUBLISHED' THEN now() END
FROM (VALUES
    ('DEMO-001', 'Mèo Lười Cà Phê', 'meo luoi ca phe quan 1 ca phe co meo tai quan am ap yen tinh',
     'DEMO_CAFE', 'DEMO_Q1', '12 Đường Minh Họa, Quận 1', 10.7769, 106.7009,
     'Quán nhỏ có mèo, góc đọc sách yên tĩnh.', '0280000001', false, 'KNOWN', 'PUBLISHED', 1),
    ('DEMO-002', 'Xưởng Gốm Đất Nung', 'xuong gom dat nung quan 3 lam gom nghe thuat',
     'DEMO_WORKSHOP', 'DEMO_Q3', '45 Đường Minh Họa, Quận 3', 10.7840, 106.6880,
     'Workshop làm gốm 2 tiếng cho hai người.', '0280000002', true, 'KNOWN', 'PUBLISHED', 2),
    ('DEMO-003', 'Rooftop Hoàng Hôn', 'rooftop hoang hon quan 1 an uong lang man rooftop ngam hoang hon',
     'DEMO_RESTAURANT', 'DEMO_Q1', '88 Đường Minh Họa, Quận 1', 10.7730, 106.7040,
     'Nhà hàng sân thượng, ngắm hoàng hôn.', '0280000003', true, 'KNOWN', 'PUBLISHED', 3),
    ('DEMO-004', 'Bar Đêm Muộn', 'bar dem muon binh thanh soi dong band nhac',
     'DEMO_BAR', 'DEMO_BT', '7 Đường Minh Họa, Bình Thạnh', 10.8030, 106.7140,
     'Mở qua đêm, có band nhạc cuối tuần.', NULL, false, 'KNOWN', 'PUBLISHED', 4),
    ('DEMO-005', 'Công Viên Ven Sông', 'cong vien ven song binh thanh van dong ven song ngoai troi',
     'DEMO_OUTDOOR', 'DEMO_BT', 'Bờ sông Minh Họa, Bình Thạnh', 10.7990, 106.7210,
     'Đi dạo miễn phí ven sông.', NULL, NULL, 'KNOWN', 'PUBLISHED', 5),
    ('DEMO-006', 'Lớp Vẽ Tranh Sơn Dầu', 'lop ve tranh son dau quan 3 ve tranh vintage',
     'DEMO_WORKSHOP', 'DEMO_Q3', '19 Đường Minh Họa, Quận 3', 10.7810, 106.6920,
     NULL, NULL, NULL, 'UNKNOWN', 'PUBLISHED', 6),
    ('DEMO-007', 'Nhà Hàng Kỷ Niệm', 'nha hang ky niem quan 1 an uong rieng tu ky niem',
     'DEMO_RESTAURANT', 'DEMO_Q1', '3 Đường Minh Họa, Quận 1', 10.7750, 106.6990,
     'Phòng riêng cho dịp kỷ niệm.', '0280000007', true, 'KNOWN', 'PUBLISHED', 7),
    ('DEMO-008', 'Quán Mới Chưa Duyệt', 'quan moi chua duyet quan 1',
     'DEMO_CAFE', 'DEMO_Q1', '99 Đường Minh Họa, Quận 1', 10.7700, 106.7000,
     'Bản nháp — không được xuất hiện ở trang công khai.', NULL, NULL, 'UNKNOWN', 'DRAFT', 0)
) AS v(code, name, search_text, category, area, address, lat, lng, description, phone, reservation,
       hours_status, status, days_ago);

-- ───────────── Giá chuẩn cho hai người (biên RULE-03) ─────────────
INSERT INTO place_price (place_id, status, standard_for_two, min_price, max_price, includes, source, verified_at)
SELECT p.id, v.status, v.std, v.min, v.max, v.includes, 'Dữ liệu minh họa', current_date
FROM (VALUES
    ('DEMO-001', 'KNOWN',   150000,  100000,  200000, '2 đồ uống'),
    ('DEMO-002', 'KNOWN',   400000,  400000,  600000, 'Phí workshop 2 người'),
    ('DEMO-003', 'KNOWN',   700000,  500000, 1000000, 'Bữa tối 2 người'),
    ('DEMO-004', 'KNOWN',   250000,  200000,  400000, '2 cocktail'),
    ('DEMO-005', 'KNOWN',        0,       0,       0, 'Miễn phí'),
    ('DEMO-006', 'UNKNOWN',   NULL,    NULL,    NULL, NULL),
    ('DEMO-007', 'KNOWN',  1200000, 900000, 1800000, 'Set kỷ niệm 2 người')
) AS v(code, status, std, min, max, includes)
JOIN place p ON p.code = v.code;

-- ───────────── Giờ mở cửa (1 = Thứ Hai … 7 = Chủ Nhật) ─────────────
-- DEMO-004 mở qua đêm 18:00–02:00 (closes_at <= opens_at, OQ-04). DEMO-006 giờ chưa rõ: không có dòng nào.
INSERT INTO opening_hours (place_id, day_of_week, opens_at, closes_at)
SELECT p.id, d.dow, v.opens, v.closes
FROM (VALUES
    ('DEMO-001', TIME '07:00', TIME '22:00'),
    ('DEMO-002', TIME '09:00', TIME '18:00'),
    ('DEMO-003', TIME '16:00', TIME '23:30'),
    ('DEMO-004', TIME '18:00', TIME '02:00'),
    ('DEMO-005', TIME '05:00', TIME '21:00'),
    ('DEMO-007', TIME '11:00', TIME '14:00'),
    ('DEMO-007', TIME '17:30', TIME '22:00')
) AS v(code, opens, closes)
JOIN place p ON p.code = v.code
CROSS JOIN generate_series(1, 7) AS d(dow)
WHERE NOT (v.code = 'DEMO-002' AND d.dow = 1); -- xưởng gốm nghỉ Thứ Hai

-- Ngoại lệ: Rooftop nghỉ 7 ngày tới; Mèo Lười mở muộn ngày mai.
INSERT INTO date_exception (place_id, on_date, closed, opens_at, closes_at, note)
SELECT id, current_date + 7, true, NULL, NULL, 'Nghỉ bảo trì (minh họa)' FROM place WHERE code = 'DEMO-003'
UNION ALL
SELECT id, current_date + 1, false, TIME '10:00', TIME '20:00', 'Mở muộn (minh họa)' FROM place WHERE code = 'DEMO-001';

-- ───────────── Nhãn ─────────────
INSERT INTO place_tag (place_id, taxonomy_id)
SELECT p.id, t.id
FROM (VALUES
    ('DEMO-001', 'CAFE'), ('DEMO-001', 'CATS'), ('DEMO-001', 'COZY'), ('DEMO-001', 'QUIET'), ('DEMO-001', 'FIRST_DATE'),
    ('DEMO-002', 'POTTERY'), ('DEMO-002', 'ARTSY'), ('DEMO-002', 'QUIET'), ('DEMO-002', 'WEEKEND'),
    ('DEMO-003', 'DINING'), ('DEMO-003', 'ROMANTIC'), ('DEMO-003', 'ROOFTOP'), ('DEMO-003', 'SUNSET'), ('DEMO-003', 'ANNIVERSARY'),
    ('DEMO-004', 'GAMES'), ('DEMO-004', 'LIVELY'), ('DEMO-004', 'LIVE_BAND'),
    ('DEMO-005', 'SPORTS'), ('DEMO-005', 'RIVERSIDE'), ('DEMO-005', 'OUTDOOR'), ('DEMO-005', 'PET_FRIENDLY'),
    ('DEMO-006', 'PAINTING'), ('DEMO-006', 'VINTAGE'),
    ('DEMO-007', 'DINING'), ('DEMO-007', 'PRIVATE'), ('DEMO-007', 'ROMANTIC'), ('DEMO-007', 'ANNIVERSARY'), ('DEMO-007', 'NON_SMOKING')
) AS v(code, tag)
JOIN place p ON p.code = v.code
JOIN taxonomy t ON t.code = v.tag AND t.group_code <> 'CATEGORY';

-- ───────────── Ảnh (tệp giả; cần FILES_PUBLIC_BASE_URL trỏ tới nơi có ảnh thì FE mới hiện) ─────────────
INSERT INTO stored_file (purpose, content_type, size_bytes, storage_key)
SELECT 'PLACE_IMAGE', 'image/jpeg', 1, 'demo/' || lower(code) || '.jpg' FROM place WHERE code LIKE 'DEMO-00%'
UNION ALL
SELECT 'COLLECTION', 'image/jpeg', 1, 'demo/collection-first-date.jpg';

INSERT INTO place_image (place_id, file_id, sort_order, is_cover, caption, source_rights)
SELECT p.id, f.id, 0, true, p.name, 'Ảnh minh họa, không dùng cho production'
FROM place p JOIN stored_file f ON f.storage_key = 'demo/' || lower(p.code) || '.jpg';

-- ───────────── Bộ sưu tập ─────────────
WITH c AS (
    INSERT INTO collection (title, body, cover_file_id, visible_from, visible_until)
    VALUES ('[Minh họa] Hẹn đầu nhẹ nhàng', 'Những nơi yên tĩnh, dễ trò chuyện cho buổi hẹn đầu.',
            (SELECT id FROM stored_file WHERE storage_key = 'demo/collection-first-date.jpg'), now() - interval '1 day', NULL)
    RETURNING id
)
INSERT INTO collection_item (collection_id, place_id, sort_order)
SELECT c.id, p.id, v.ord
FROM c, (VALUES ('DEMO-001', 0), ('DEMO-002', 1), ('DEMO-005', 2), ('DEMO-008', 3)) AS v(code, ord)
JOIN place p ON p.code = v.code;

WITH c AS (
    INSERT INTO collection (title, body, visible_from, visible_until)
    VALUES ('[Minh họa] Dịp kỷ niệm', 'Không gian riêng tư, lãng mạn.', NULL, NULL)
    RETURNING id
)
INSERT INTO collection_item (collection_id, place_id, sort_order)
SELECT c.id, p.id, v.ord
FROM c, (VALUES ('DEMO-003', 0), ('DEMO-007', 1)) AS v(code, ord)
JOIN place p ON p.code = v.code;

COMMIT;
