package vn.gowithbae.content;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import vn.gowithbae.api.AdminContentApiDelegate;
import vn.gowithbae.api.model.AdminNotificationRequest;
import vn.gowithbae.api.model.AdminNotificationSent;
import vn.gowithbae.api.model.AdminNotificationSentPage;
import vn.gowithbae.api.model.AdminSiteContent;
import vn.gowithbae.api.model.SiteContent;
import vn.gowithbae.common.web.Problems;
import vn.gowithbae.platform.CurrentUser;

/** Sửa nội dung trang tĩnh và gửi thông báo: chỉ SUPER_ADMIN. */
@Service
public class AdminContentDelegate implements AdminContentApiDelegate {

    private final SiteContentService content;
    private final AdminNotificationService notifications;
    private final CurrentUser current;

    public AdminContentDelegate(SiteContentService content, AdminNotificationService notifications, CurrentUser current) {
        this.content = content;
        this.notifications = notifications;
        this.current = current;
    }

    @Override
    public ResponseEntity<AdminSiteContent> adminGetSiteContent() {
        current.requireRole("SUPER_ADMIN");
        return ResponseEntity.ok(content.currentForAdmin());
    }

    @Override
    public ResponseEntity<AdminSiteContent> adminUpdateSiteContent(SiteContent body) {
        current.requireRole("SUPER_ADMIN");
        return ResponseEntity.ok(content.replace(body, current.id()));
    }

    @Override
    public ResponseEntity<AdminNotificationSent> adminSendNotification(AdminNotificationRequest body) {
        current.requireRole("SUPER_ADMIN");
        return ResponseEntity.status(HttpStatus.CREATED).body(notifications.send(body, current.id()));
    }

    @Override
    public ResponseEntity<AdminNotificationSentPage> adminListSentNotifications(Integer page, Integer size) {
        current.requireRole("SUPER_ADMIN");
        int p = page == null ? 0 : page;
        int s = size == null ? 20 : size;
        if (p < 0 || s < 1 || s > 100) {
            throw Problems.badRequest("Phân trang không hợp lệ");
        }
        return ResponseEntity.ok(notifications.sent(p, s));
    }
}
