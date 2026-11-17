package vn.gowithbae.admin;

import org.springframework.stereotype.Component;

import vn.gowithbae.platform.CurrentUser;

/**
 * Ma trận quyền quản trị nội dung (phần B). shortcut: OQ-18 chưa chốt ma trận chi tiết; đây là đề xuất
 * "mặc định từ chối". PO chốt xong chỉ sửa ở file này. Vai trò lấy từ DB qua {@link CurrentUser} (phần A),
 * không tin claim trong token.
 * <ul>
 * <li>Xem địa điểm/danh mục/bộ sưu tập: EDITOR, REVIEWER, SUPER_ADMIN</li>
 * <li>Biên tập nháp, gửi duyệt, ảnh, CSV, bộ sưu tập: EDITOR, SUPER_ADMIN</li>
 * <li>Duyệt/trả/ẩn/ngừng/khôi phục: REVIEWER, SUPER_ADMIN; không ai tự duyệt nơi mình tạo (RULE-21)</li>
 * <li>Nhãn và khu vực: SUPER_ADMIN</li>
 * <li>SUPPORT: không có quyền ở phần nội dung</li>
 * </ul>
 */
@Component
public class AdminPermissions {

    private final CurrentUser currentUser;

    public AdminPermissions(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    public void requireContentRead() {
        currentUser.requireAnyRole("EDITOR", "REVIEWER", "SUPER_ADMIN");
    }

    public void requireContentEdit() {
        currentUser.requireAnyRole("EDITOR", "SUPER_ADMIN");
    }

    public void requireContentReview() {
        currentUser.requireAnyRole("REVIEWER", "SUPER_ADMIN");
    }

    public void requireTaxonomyAdmin() {
        currentUser.requireAnyRole("SUPER_ADMIN");
    }
}
