package vn.gowithbae.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class SmtpOtpDelivery implements OtpDelivery {
    private static final Logger log = LoggerFactory.getLogger(SmtpOtpDelivery.class);
    private final ObjectProvider<JavaMailSender> senders;
    private final String from;

    public SmtpOtpDelivery(ObjectProvider<JavaMailSender> senders, @Value("${app.mail.from:}") String from) {
        this.senders = senders;
        this.from = from;
    }

    @Override
    public void send(String email, String code, String purpose) {
        var sender = senders.getIfAvailable();
        if (sender == null || from.isBlank()) {
            log.error("[OTP] Dịch vụ email chưa sẵn sàng (sender={}, from={})", sender, from);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Dịch vụ email chưa sẵn sàng");
        }
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("Mã xác minh GoWithBae");
        message.setText("Mã xác minh của bạn: " + code + ". Không chia sẻ mã này với người khác.");
        try {
            log.info("[OTP] Gửi mã xác thực [{}] đến email [{}] qua SMTP [{}]", code, email, from);
            sender.send(message);
            log.info("[OTP] Gửi thành công mã xác thực đến [{}]", email);
        } catch (MailException ex) {
            log.error("[OTP] Lỗi khi gửi email đến [{}]: {}", email, ex.getMessage(), ex);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chưa gửi được email. Hãy thử lại sau.");
        }
    }
}
