package vn.gowithbae.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** memberOtpEnabled=false (CR-002): thành viên chỉ đăng nhập Google; mã email chỉ còn cho tài khoản nội bộ.
 * Quyết định triển khai A; cấu hình được để thay đổi sau review OQ-06, không phải số đã duyệt trong SRS. */
@ConfigurationProperties("app.auth")
public record AuthPolicy(@DefaultValue("PT5M") Duration otpTtl,
        @DefaultValue("PT1M") Duration resendCooldown, @DefaultValue("PT8H") Duration sessionTtl,
        @DefaultValue("5") int maxAttempts, @DefaultValue("5") int requestsPerHour,
        @DefaultValue("30") int ipRequestsPerHour, @DefaultValue("30") int verifiesPerQuarterHour,
        @DefaultValue("false") boolean memberOtpEnabled) {
    public AuthPolicy {
        if (otpTtl.isNegative() || otpTtl.isZero() || sessionTtl.isNegative() || sessionTtl.isZero()
                || resendCooldown.isNegative() || maxAttempts < 1 || requestsPerHour < 1 || ipRequestsPerHour < 1
                || verifiesPerQuarterHour < 1) throw new IllegalArgumentException("Cấu hình xác thực không hợp lệ");
    }
}
