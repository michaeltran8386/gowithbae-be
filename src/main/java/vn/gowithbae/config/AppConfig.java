package vn.gowithbae.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import vn.gowithbae.common.TimeHelper;

/** Cấu hình bean dùng chung (múi giờ hệ thống Asia/Ho_Chi_Minh). */
@Configuration
public class AppConfig {

    @Bean
    Clock systemClock() {
        return Clock.system(TimeHelper.VN_ZONE);
    }
}
