package vn.gowithbae.admin;

import vn.gowithbae.common.security.CurrentUser;
import vn.gowithbae.common.web.Problems;

/**
 * Ma trận quyền quản trị nội dung (phần B). shortcut: OQ-18 chưa chốt ma trận chi tiết; đây là đề xuất
 * "mặc định từ chối". PO chốt xong chỉ sửa ở file này.
 * <ul>
 * <li>Xem địa điểm/danh mục/bộ sưu tập: EDITOR, REVIEWER, SUPER_ADMIN</li>
 * <li>Biên tập nháp, gửi duyệt, ảnh, CSV, bộ sưu tập: EDITOR, SUPER_ADMIN</li>
 * <li>Duyệt/trả/ẩn/ngừng/khôi phục: REVIEWER, SUPER_ADMIN; không ai tự duyệt nơi mình tạo (RULE-21)</li>
 * <li>Nhãn và khu vực: SUPER_ADMIN</li>
 * <li>SUPPORT: không có quyền ở phần nội dung</li>
 * </ul>
 */
public final class AdminPermissions {

    private AdminPermissions() {
    }

    public static void requireContentRead() {
        require("Không có quyền xem nội dung quản trị", "EDITOR", "REVIEWER", "SUPER_ADMIN");
    }

    public static void requireContentEdit() {
        require("Không có quyền biên tập nội dung", "EDITOR", "SUPER_ADMIN");
    }

    public static void requireContentReview() {
        require("Không có quyền duyệt nội dung", "REVIEWER", "SUPER_ADMIN");
    }

    public static void requireTaxonomyAdmin() {
        require("Không có quyền quản lý nhãn và khu vực", "SUPER_ADMIN");
    }

    private static void require(String message, String... roles) {
        if (!CurrentUser.hasAnyRole(roles)) {
            throw Problems.forbidden(message);
        }
    }
}
