# Sinh scripts/dev-seed.sql (dữ liệu minh họa). Sửa các danh sách bên dưới rồi chạy từ thư mục gowithbae-be:
#   python -X utf8 scripts/gen_dev_seed.py scripts/dev-seed.sql
import sys

AREAS = [  # code, name, lat, lng
    ("DEMO_Q1", "Quận 1", 10.7760, 106.7009),
    ("DEMO_Q3", "Quận 3", 10.7830, 106.6870),
    ("DEMO_BT", "Bình Thạnh", 10.8050, 106.7140),
    ("DEMO_PN", "Phú Nhuận", 10.7990, 106.6800),
    ("DEMO_Q7", "Quận 7", 10.7340, 106.7210),
    ("DEMO_TD", "Thủ Đức", 10.8500, 106.7700),
]
CATEGORIES = [
    ("DEMO_CAFE", "Quán cà phê"), ("DEMO_RESTAURANT", "Nhà hàng"), ("DEMO_WORKSHOP", "Workshop"),
    ("DEMO_BAR", "Bar / pub"), ("DEMO_OUTDOOR", "Công viên / ngoài trời"), ("DEMO_CINEMA", "Rạp phim"),
    ("DEMO_GALLERY", "Bảo tàng / phòng tranh"), ("DEMO_GAME", "Khu vui chơi"),
]

# Lịch giờ: danh sách (các thứ, mở, đóng). 1 = Thứ Hai … 7 = Chủ Nhật. None = giờ chưa rõ.
ALL = [1, 2, 3, 4, 5, 6, 7]
HOURS = {
    "CAFE": [(ALL, "07:00", "22:00")],
    "CAFE_EARLY": [(ALL, "06:00", "21:00")],
    "DAY": [(ALL, "08:00", "22:00")],
    "EVE": [(ALL, "16:00", "23:30")],
    "LATE": [(ALL, "18:00", "02:00")],                       # qua đêm
    "LATE_WKND": [([1, 2, 3, 4], "19:00", "00:00"), ([5, 6, 7], "19:00", "03:00")],
    "WORK": [([2, 3, 4, 5, 6, 7], "09:00", "18:00")],         # nghỉ Thứ Hai
    "LUNCH_DINNER": [(ALL, "11:00", "14:00"), (ALL, "17:30", "22:00")],
    "PARK": [(ALL, "05:00", "21:00")],
    "WEEKEND": [([6, 7], "09:00", "21:00")],                  # chỉ cuối tuần
    "CINEMA": [(ALL, "09:00", "00:00")],                      # đóng nửa đêm
    "MUSEUM": [([2, 3, 4, 5, 6, 7], "08:30", "17:00")],
    "UNKNOWN": None,
}

# code, tên, loại hình, khu vực, dlat, dlng, mô tả, điện thoại, đặt chỗ, giờ, giá(status, chuẩn, min, max, gồm), nhãn, trạng thái, ngày kiểm tra trước
P = [
    ("DEMO-001", "Mèo Lười Cà Phê", "DEMO_CAFE", "DEMO_Q1", 0.0009, 0.0000, "Quán nhỏ có mèo, góc đọc sách yên tĩnh.", "0280000001", False, "CAFE", ("KNOWN", 150000, 100000, 200000, "2 đồ uống"), "CAFE CATS COZY QUIET FIRST_DATE", "PUBLISHED", 1),
    ("DEMO-002", "Xưởng Gốm Đất Nung", "DEMO_WORKSHOP", "DEMO_Q3", 0.0010, 0.0010, "Workshop làm gốm 2 tiếng cho hai người.", "0280000002", True, "WORK", ("KNOWN", 400000, 400000, 600000, "Phí workshop 2 người"), "POTTERY ARTSY QUIET WEEKEND", "PUBLISHED", 2),
    ("DEMO-003", "Rooftop Hoàng Hôn", "DEMO_RESTAURANT", "DEMO_Q1", -0.0030, 0.0031, "Nhà hàng sân thượng, ngắm hoàng hôn.", "0280000003", True, "EVE", ("KNOWN", 700000, 500000, 1000000, "Bữa tối 2 người"), "DINING ROMANTIC ROOFTOP SUNSET ANNIVERSARY", "PUBLISHED", 3),
    ("DEMO-004", "Bar Đêm Muộn", "DEMO_BAR", "DEMO_BT", -0.0020, 0.0000, "Mở qua đêm, có band nhạc cuối tuần.", None, False, "LATE", ("KNOWN", 250000, 200000, 400000, "2 cocktail"), "GAMES LIVELY LIVE_BAND", "PUBLISHED", 4),
    ("DEMO-005", "Công Viên Ven Sông", "DEMO_OUTDOOR", "DEMO_BT", -0.0060, 0.0070, "Đi dạo miễn phí ven sông.", None, None, "PARK", ("KNOWN", 0, 0, 0, "Miễn phí"), "SPORTS RIVERSIDE OUTDOOR PET_FRIENDLY", "PUBLISHED", 5),
    ("DEMO-006", "Lớp Vẽ Tranh Sơn Dầu", "DEMO_WORKSHOP", "DEMO_Q3", -0.0020, 0.0050, None, None, None, "UNKNOWN", ("UNKNOWN", None, None, None, None), "PAINTING VINTAGE", "PUBLISHED", 6),
    ("DEMO-007", "Nhà Hàng Kỷ Niệm", "DEMO_RESTAURANT", "DEMO_Q1", -0.0010, -0.0019, "Phòng riêng cho dịp kỷ niệm.", "0280000007", True, "LUNCH_DINNER", ("KNOWN", 1200000, 900000, 1800000, "Set kỷ niệm 2 người"), "DINING PRIVATE ROMANTIC ANNIVERSARY NON_SMOKING", "PUBLISHED", 7),
    ("DEMO-008", "Quán Mới Chưa Duyệt", "DEMO_CAFE", "DEMO_Q1", -0.0060, 0.0000, "Bản nháp — không được xuất hiện ở trang công khai.", None, None, "UNKNOWN", None, "CAFE", "DRAFT", 0),
    # ---- Quận 1 ----
    ("DEMO-009", "Cà Phê Sách Thư Viện", "DEMO_CAFE", "DEMO_Q1", 0.0020, -0.0030, "Không gian đọc sách, cấm nói to.", "0280000009", False, "CAFE_EARLY", ("KNOWN", 120000, 90000, 160000, "2 đồ uống"), "CAFE QUIET MINIMAL NON_SMOKING FIRST_DATE", "PUBLISHED", 2),
    ("DEMO-010", "Pub Nhạc Sống Phố Cổ", "DEMO_BAR", "DEMO_Q1", 0.0012, 0.0040, "Band acoustic mỗi tối.", "0280000010", False, "LATE_WKND", ("KNOWN", 399000, 300000, 600000, "2 bia thủ công"), "GAMES LIVELY LIVE_BAND BIRTHDAY", "PUBLISHED", 9),
    ("DEMO-011", "Bảo Tàng Mỹ Thuật Mini", "DEMO_GALLERY", "DEMO_Q1", 0.0035, 0.0012, "Triển lãm tranh đương đại, vé vào cửa rẻ.", "0280000011", False, "MUSEUM", ("KNOWN", 60000, 60000, 60000, "2 vé vào cửa"), "EXHIBITION ARTSY QUIET WHEELCHAIR", "PUBLISHED", 12),
    ("DEMO-012", "Rạp Phim Độc Lập", "DEMO_CINEMA", "DEMO_Q1", -0.0042, -0.0025, "Chiếu phim nghệ thuật, ghế đôi.", "0280000012", True, "CINEMA", ("KNOWN", 200000, 180000, 260000, "2 vé + bắp nước"), "MOVIE PRIVATE ROMANTIC FIRST_DATE", "PUBLISHED", 3),
    ("DEMO-013", "Tiệm Bánh Tự Làm", "DEMO_WORKSHOP", "DEMO_Q1", 0.0051, -0.0011, "Tự làm bánh kem 90 phút.", "0280000013", True, "WORK", ("KNOWN", 550000, 450000, 650000, "Nguyên liệu + hướng dẫn"), "BAKING COZY MODERN BIRTHDAY", "PUBLISHED", 15),
    # ---- Quận 3 ----
    ("DEMO-014", "Cà Phê Sân Vườn Cũ", "DEMO_CAFE", "DEMO_Q3", 0.0025, -0.0020, "Biệt thự cũ, nhiều cây xanh.", "0280000014", False, "CAFE", ("KNOWN", 199000, 150000, 250000, "2 đồ uống"), "CAFE GARDEN GREENERY VINTAGE QUIET PET_FRIENDLY", "PUBLISHED", 4),
    ("DEMO-015", "Bistro Ánh Nến", "DEMO_RESTAURANT", "DEMO_Q3", -0.0015, 0.0030, "Món Âu, ánh nến, nhạc jazz nhẹ.", "0280000015", True, "LUNCH_DINNER", ("KNOWN", 850000, 700000, 1100000, "2 phần ăn chính + rượu"), "DINING ROMANTIC PRIVATE ANNIVERSARY", "PUBLISHED", 8),
    ("DEMO-016", "Board Game Café", "DEMO_GAME", "DEMO_Q3", 0.0040, 0.0035, "Hơn 300 trò board game.", "0280000016", False, "DAY", ("KNOWN", 140000, 100000, 180000, "Phí chơi + 2 nước"), "GAMES LIVELY COZY WEEKEND", "PUBLISHED", 6),
    ("DEMO-017", "Studio Nến Thơm", "DEMO_WORKSHOP", "DEMO_Q3", -0.0038, -0.0028, "Tự pha mùi và đổ nến, mang về.", "0280000017", True, "WEEKEND", ("KNOWN", 350000, 300000, 420000, "2 hũ nến"), "ARTSY COZY MINIMAL FIRST_DATE", "PUBLISHED", 10),
    # ---- Bình Thạnh ----
    ("DEMO-018", "Cà Phê Chó Corgi", "DEMO_CAFE", "DEMO_BT", 0.0030, -0.0040, "Chơi cùng 6 bé corgi.", "0280000018", False, "DAY", ("KNOWN", 180000, 150000, 220000, "2 đồ uống"), "CAFE DOGS PET_FRIENDLY LIVELY", "PUBLISHED", 2),
    ("DEMO-019", "Thuyền Ngắm Sông Đêm", "DEMO_OUTDOOR", "DEMO_BT", -0.0080, 0.0060, "Du thuyền 60 phút trên sông.", "0280000019", True, "EVE", ("KNOWN", 700001, 650000, 900000, "Vé 2 người + nước"), "RIVERSIDE SUNSET ROMANTIC ANNIVERSARY OUTDOOR", "PUBLISHED", 5),
    ("DEMO-020", "Quán Nướng Sân Thượng", "DEMO_RESTAURANT", "DEMO_BT", 0.0015, 0.0025, "Nướng tại bàn, view thành phố.", "0280000020", False, "EVE", ("KNOWN", 450000, 350000, 600000, "Set nướng 2 người"), "DINING ROOFTOP LIVELY BIRTHDAY", "PUBLISHED", 11),
    ("DEMO-021", "Khu Trò Chơi Điện Tử", "DEMO_GAME", "DEMO_BT", -0.0025, -0.0035, "Máy gắp thú, bowling, bi-a.", None, False, "DAY", ("UNKNOWN", None, None, None, None), "GAMES SPORTS LIVELY", "PUBLISHED", 20),
    # ---- Phú Nhuận ----
    ("DEMO-022", "Trà Chiều Kiểu Anh", "DEMO_CAFE", "DEMO_PN", 0.0010, 0.0015, "Bánh ngọt và trà, đồ sứ cổ.", "0280000022", True, "WORK", ("KNOWN", 380000, 320000, 450000, "Set trà chiều 2 người"), "CAFE VINTAGE COZY QUIET ANNIVERSARY", "PUBLISHED", 3),
    ("DEMO-023", "Bếp Chay An Nhiên", "DEMO_RESTAURANT", "DEMO_PN", -0.0020, -0.0010, "Ẩm thực chay, không gian thiền.", "0280000023", False, "LUNCH_DINNER", ("KNOWN", 260000, 200000, 320000, "2 phần ăn"), "DINING QUIET GARDEN NON_SMOKING WHEELCHAIR", "PUBLISHED", 7),
    ("DEMO-024", "Phòng Tranh Ánh Sáng", "DEMO_GALLERY", "DEMO_PN", 0.0032, -0.0028, "Triển lãm ánh sáng tương tác.", "0280000024", False, "WEEKEND", ("KNOWN", 300000, 300000, 300000, "2 vé"), "EXHIBITION MODERN ARTSY WEEKEND", "PUBLISHED", 14),
    # ---- Quận 7 ----
    ("DEMO-025", "Hồ Bán Nguyệt Đi Dạo", "DEMO_OUTDOOR", "DEMO_Q7", 0.0010, 0.0005, "Đi dạo quanh hồ, cầu đi bộ.", None, None, "PARK", ("KNOWN", 0, 0, 0, "Miễn phí"), "SPORTS LAKESIDE GREENERY SUNSET OUTDOOR PET_FRIENDLY", "PUBLISHED", 1),
    ("DEMO-026", "Nhà Hàng Ven Hồ", "DEMO_RESTAURANT", "DEMO_Q7", 0.0018, 0.0020, "Hải sản, bàn sát mặt hồ.", "0280000026", True, "LUNCH_DINNER", ("KNOWN", 950000, 800000, 1300000, "Set hải sản 2 người"), "DINING LAKESIDE ROMANTIC ANNIVERSARY", "PUBLISHED", 6),
    ("DEMO-027", "Sân Trượt Patin", "DEMO_GAME", "DEMO_Q7", -0.0030, -0.0010, "Thuê giày, nhạc sôi động.", "0280000027", False, "DAY", ("KNOWN", 220000, 200000, 260000, "2 vé + thuê giày"), "SPORTS LIVELY GAMES WEEKEND", "PUBLISHED", 9),
    ("DEMO-028", "Rạp Phim Ghế Nằm", "DEMO_CINEMA", "DEMO_Q7", 0.0045, -0.0035, "Ghế nằm, chăn, phục vụ tại ghế.", "0280000028", True, "CINEMA", ("KNOWN", 500000, 450000, 600000, "2 vé ghế nằm + combo"), "MOVIE PRIVATE COZY ANNIVERSARY", "PUBLISHED", 4),
    # ---- Thủ Đức ----
    ("DEMO-029", "Vườn Dâu Tự Hái", "DEMO_OUTDOOR", "DEMO_TD", 0.0040, 0.0050, "Tự hái dâu, picnic bãi cỏ.", "0280000029", True, "WEEKEND", ("KNOWN", 160000, 120000, 200000, "2 vé + 500g dâu"), "GREENERY OUTDOOR PET_FRIENDLY WEEKEND", "PUBLISHED", 8),
    ("DEMO-030", "Cà Phê Võng Ven Kênh", "DEMO_CAFE", "DEMO_TD", -0.0030, 0.0020, "Nằm võng, gió kênh, giá sinh viên.", None, False, "CAFE_EARLY", ("KNOWN", 60000, 40000, 80000, "2 đồ uống"), "CAFE RIVERSIDE GREENERY QUIET", "PUBLISHED", 2),
    ("DEMO-031", "Xưởng Làm Đồ Da", "DEMO_WORKSHOP", "DEMO_TD", 0.0012, -0.0040, "Tự làm ví da khắc tên.", "0280000031", True, "WORK", ("KNOWN", 620000, 500000, 750000, "2 sản phẩm da"), "ARTSY VINTAGE QUIET BIRTHDAY", "PUBLISHED", 13),
    ("DEMO-032", "Beer Garden Sân Cỏ", "DEMO_BAR", "DEMO_TD", -0.0050, -0.0030, "Bia tươi, sân cỏ, chiếu bóng đá.", "0280000032", False, "LATE", ("KNOWN", 300000, 250000, 400000, "2 bia + đồ nhắm"), "LIVELY OUTDOOR LIVE_BAND DOGS", "PUBLISHED", 5),
    # ---- Trạng thái quản trị (không hiện ở trang công khai) ----
    ("DEMO-033", "Quán Chờ Duyệt", "DEMO_CAFE", "DEMO_PN", 0.0055, 0.0045, "Đã gửi duyệt, chờ người duyệt.", "0280000033", False, "CAFE", ("KNOWN", 100000, 80000, 120000, "2 đồ uống"), "CAFE COZY", "PENDING", 1),
    ("DEMO-034", "Quán Tạm Ẩn Sửa Chữa", "DEMO_RESTAURANT", "DEMO_Q3", 0.0060, 0.0060, "Đang sửa chữa, tạm ẩn.", "0280000034", False, "LUNCH_DINNER", ("KNOWN", 300000, 250000, 350000, "2 phần ăn"), "DINING QUIET", "HIDDEN", 30),
    ("DEMO-035", "Quán Đã Ngừng Hoạt Động", "DEMO_BAR", "DEMO_Q1", -0.0070, 0.0070, "Đã đóng cửa vĩnh viễn.", None, False, "LATE", ("KNOWN", 250000, 200000, 300000, "2 đồ uống"), "LIVELY", "INACTIVE", 60),
]

COLLECTIONS = [  # tiêu đề, mô tả, from (SQL), until (SQL), các mã
    ("[Minh họa] Hẹn đầu nhẹ nhàng", "Những nơi yên tĩnh, dễ trò chuyện cho buổi hẹn đầu.", "now() - interval '1 day'", "NULL",
     ["DEMO-001", "DEMO-009", "DEMO-012", "DEMO-017", "DEMO-014", "DEMO-008"]),
    ("[Minh họa] Dịp kỷ niệm", "Không gian riêng tư, lãng mạn.", "NULL", "NULL",
     ["DEMO-003", "DEMO-007", "DEMO-015", "DEMO-019", "DEMO-026", "DEMO-028"]),
    ("[Minh họa] Cuối tuần ngoài trời", "Đi dạo, picnic, vận động nhẹ.", "NULL", "NULL",
     ["DEMO-005", "DEMO-025", "DEMO-029", "DEMO-027", "DEMO-030"]),
    ("[Minh họa] Hẹn hò dưới 200k", "Vui mà không tốn kém.", "NULL", "NULL",
     ["DEMO-005", "DEMO-025", "DEMO-030", "DEMO-011", "DEMO-009", "DEMO-016", "DEMO-018", "DEMO-014"]),
    ("[Minh họa] Cú đêm", "Mở muộn, có nơi qua đêm.", "NULL", "NULL",
     ["DEMO-004", "DEMO-010", "DEMO-032", "DEMO-012", "DEMO-035"]),
    ("[Minh họa] Bộ sưu tập đã hết hạn", "Không được hiện (visible_until đã qua).", "now() - interval '30 days'", "now() - interval '1 day'",
     ["DEMO-001"]),
    ("[Minh họa] Bộ sưu tập sắp ra mắt", "Không được hiện (visible_from ở tương lai).", "now() + interval '7 days'", "NULL",
     ["DEMO-003"]),
]

# Ngày ngoại lệ: mã, số ngày từ hôm nay, đóng?, mở, đóng, ghi chú
EXCEPTIONS = [
    ("DEMO-003", 7, True, None, None, "Nghỉ bảo trì"),
    ("DEMO-001", 1, False, "10:00", "20:00", "Mở muộn, đóng sớm"),
    ("DEMO-004", 2, True, None, None, "Nghỉ sự kiện riêng"),
    ("DEMO-015", 3, False, "17:30", "23:30", "Mở thêm giờ dịp lễ"),
    ("DEMO-025", 5, True, None, None, "Đóng cửa vệ sinh hồ"),
]


def q(s):
    return "NULL" if s is None else "'" + s.replace("'", "''") + "'"


def b(v):
    return "NULL" if v is None else ("true" if v else "false")


area = {a[0]: a for a in AREAS}
out = []
w = out.append
w("""-- ════════════════════════════════════════════════════════════════════
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
""")

w("-- ───────────── Khu vực và loại hình (V2 chưa seed, chờ OQ-01) ─────────────")
w("INSERT INTO area (code, name) VALUES\n" + ",\n".join(
    f"    ({q(c)}, {q(n + ' (minh họa)')})" for c, n, _, _ in AREAS) + ";\n")
w("INSERT INTO taxonomy (group_code, code, name) VALUES\n" + ",\n".join(
    f"    ('CATEGORY', {q(c)}, {q(n)})" for c, n in CATEGORIES) + ";\n")

w("-- ───────────── Địa điểm ─────────────")
rows = []
for (code, name, cat, ar, dlat, dlng, desc, phone, resv, hours, price, tags, status, days) in P:
    _, _, lat, lng = area[ar]
    hs = "UNKNOWN" if HOURS[hours] is None else "KNOWN"
    rows.append(f"    ({q(code)}, {q(name)}, {q(cat)}, {q(ar)}, {q(name.split()[0] + ' số ' + code[-3:] + ', đường Minh Họa')}, "
                f"{lat + dlat:.4f}, {lng + dlng:.4f}, {q(desc)}, {q(phone)}, {b(resv)}, '{hs}', '{status}', {days})")
w("""INSERT INTO place (code, name, search_text, category_id, area_id, address, lat, lng, description, phone,
                   reservation_recommended, hours_status, status, source, verified_at, created_by, published_at)
SELECT v.code, v.name, '',
       (SELECT id FROM taxonomy WHERE code = v.category),
       (SELECT id FROM area WHERE code = v.area),
       v.address, v.lat, v.lng, v.description, v.phone, v.reservation, v.hours_status, v.status,
       'Dữ liệu minh họa', current_date - v.days_ago, NULL,
       CASE WHEN v.status IN ('PUBLISHED', 'HIDDEN', 'INACTIVE') THEN now() - make_interval(days => v.days_ago) END
FROM (VALUES
""" + ",\n".join(rows) + """
) AS v(code, name, category, area, address, lat, lng, description, phone, reservation, hours_status, status, days_ago);
""")

w("-- ───────────── Giá chuẩn cho hai người (biên RULE-03) ─────────────")
prow = []
for p in P:
    if p[10] is None:
        continue
    st, std, mn, mx, inc = p[10]
    f = lambda x: "NULL" if x is None else str(x)
    prow.append(f"    ({q(p[0])}, '{st}', {f(std)}, {f(mn)}, {f(mx)}, {q(inc)})")
w("""INSERT INTO place_price (place_id, status, standard_for_two, min_price, max_price, includes, source, verified_at)
SELECT p.id, v.status, v.std::bigint, v.min::bigint, v.max::bigint, v.includes, 'Dữ liệu minh họa', current_date
FROM (VALUES
""" + ",\n".join(prow) + """
) AS v(code, status, std, min, max, includes)
JOIN place p ON p.code = v.code;
""")

w("-- ───────────── Giờ mở cửa (1 = Thứ Hai … 7 = Chủ Nhật; closes ≤ opens = qua đêm) ─────────────")
hrow = []
for p in P:
    sched = HOURS[p[9]]
    if sched is None:
        continue
    for days, o, c in sched:
        for d in days:
            hrow.append(f"    ({q(p[0])}, {d}, TIME '{o}', TIME '{c}')")
w("""INSERT INTO opening_hours (place_id, day_of_week, opens_at, closes_at)
SELECT p.id, v.dow, v.opens, v.closes
FROM (VALUES
""" + ",\n".join(hrow) + """
) AS v(code, dow, opens, closes)
JOIN place p ON p.code = v.code;
""")

w("-- Ngày ngoại lệ, tính từ hôm nay")
erow = []
for code, days, closed, o, c, note in EXCEPTIONS:
    erow.append(f"    ({q(code)}, {days}, {b(closed)}, {('TIME ' + q(o)) if o else 'NULL::time'}, {('TIME ' + q(c)) if c else 'NULL::time'}, {q(note + ' (minh họa)')})")
w("""INSERT INTO date_exception (place_id, on_date, closed, opens_at, closes_at, note)
SELECT p.id, current_date + v.days, v.closed, v.opens, v.closes, v.note
FROM (VALUES
""" + ",\n".join(erow) + """
) AS v(code, days, closed, opens, closes, note)
JOIN place p ON p.code = v.code;
""")

w("-- ───────────── Nhãn ─────────────")
trow = []
for p in P:
    for t in p[11].split():
        trow.append(f"({q(p[0])}, {q(t)})")
lines = []
for i in range(0, len(trow), 6):
    lines.append("    " + ", ".join(trow[i:i + 6]))
w("""INSERT INTO place_tag (place_id, taxonomy_id)
SELECT p.id, t.id
FROM (VALUES
""" + ",\n".join(lines) + """
) AS v(code, tag)
JOIN place p ON p.code = v.code
JOIN taxonomy t ON t.code = v.tag AND t.group_code <> 'CATEGORY';
""")

w("""-- ───────────── Ảnh (tệp giả; cần FILES_PUBLIC_BASE_URL trỏ tới nơi có ảnh thì FE mới hiện) ─────────────
INSERT INTO stored_file (purpose, content_type, size_bytes, storage_key)
SELECT 'PLACE_IMAGE', 'image/jpeg', 1, 'demo/' || lower(code) || '.jpg' FROM place WHERE code LIKE 'DEMO-%';

INSERT INTO place_image (place_id, file_id, sort_order, is_cover, caption, source_rights)
SELECT p.id, f.id, 0, true, p.name, 'Ảnh minh họa, không dùng cho production'
FROM place p JOIN stored_file f ON f.storage_key = 'demo/' || lower(p.code) || '.jpg';
""")

w("-- ───────────── Bộ sưu tập ─────────────")
for title, body, frm, until, codes in COLLECTIONS:
    vals = ", ".join(f"({q(c)}, {i})" for i, c in enumerate(codes))
    w(f"""WITH c AS (
    INSERT INTO collection (title, body, visible_from, visible_until)
    VALUES ({q(title)}, {q(body)}, {frm}, {until})
    RETURNING id
)
INSERT INTO collection_item (collection_id, place_id, sort_order)
SELECT c.id, p.id, v.ord
FROM c, (VALUES {vals}) AS v(code, ord)
JOIN place p ON p.code = v.code;
""")

# search_text = tên + khu vực + nhãn đang dùng, bỏ dấu, chữ thường (cùng quy tắc PlacesDelegate.normalize)
lower_from = "àáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ"
lower_to = "a" * 17 + "e" * 11 + "i" * 5 + "o" * 17 + "u" * 11 + "y" * 5 + "d"
upper_from = lower_from.upper()
assert len(lower_from) == len(lower_to) == len(upper_from) == 67, (len(lower_from), len(lower_to), len(upper_from))
w(f"""-- ───────────── Chuỗi tìm kiếm không dấu ─────────────
UPDATE place p SET search_text = lower(translate(
    concat_ws(' ', p.name, a.name,
              (SELECT string_agg(t.name, ' ' ORDER BY t.group_code, t.code)
               FROM place_tag pt JOIN taxonomy t ON t.id = pt.taxonomy_id
               WHERE pt.place_id = p.id AND t.status = 'ACTIVE')),
    '{lower_from}{upper_from}',
    '{lower_to}{lower_to}'))
FROM area a
WHERE a.id = p.area_id AND p.code LIKE 'DEMO-%';

COMMIT;
""")

open(sys.argv[1], "w", encoding="utf-8", newline="\n").write("\n".join(out))
print("places", len(P), "hours rows", len(hrow), "tags", len(trow))
