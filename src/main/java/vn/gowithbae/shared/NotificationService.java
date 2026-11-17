package vn.gowithbae.shared;

import java.util.UUID;

import org.springframework.lang.Nullable;

/** Hợp đồng tạo thông báo trong ứng dụng theo TEAM_SPLIT (cung cấp bởi Người A, cài đặt DB dùng chung). */
public interface NotificationService {

    void notify(
            UUID recipientId,
            String type,
            @Nullable String objectType,
            @Nullable UUID objectId,
            String summary);
}
