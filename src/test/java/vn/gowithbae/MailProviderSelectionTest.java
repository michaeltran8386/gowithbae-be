package vn.gowithbae;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

import vn.gowithbae.auth.BrevoOtpDelivery;
import vn.gowithbae.auth.OtpDelivery;

/** app.mail.provider=brevo thì OTP chỉ gửi qua Brevo (không còn bean SMTP). Cần Docker. */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"app.jwt.secret=test-secret-test-secret-test-secret-123", "DB_URL=", "DB_USERNAME=", "DB_PASSWORD=",
        "app.mail.provider=brevo", "app.mail.brevo.api-key=xkeysib-test", "app.mail.from=no-reply@gowithbae.test"})
class MailProviderSelectionTest {

    @Autowired ApplicationContext context;

    @Test
    void brevoIsTheOnlyOtpDelivery() {
        assertThat(context.getBeansOfType(OtpDelivery.class)).hasSize(1)
                .allSatisfy((name, bean) -> assertThat(bean).isInstanceOf(BrevoOtpDelivery.class));
    }
}
