package vn.gowithbae.auth;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Gửi OTP qua SMTP (mặc định; local dùng Mailpit). Máy chủ chặn cổng SMTP thì dùng {@link BrevoOtpDelivery}. */
@Component
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "smtp", matchIfMissing = true)
public class SmtpOtpDelivery implements OtpDelivery {
    private final ObjectProvider<JavaMailSender> senders;
    private final String from;
    public SmtpOtpDelivery(ObjectProvider<JavaMailSender> senders, @Value("${app.mail.from:}") String from) {
        this.senders = senders;
        this.from = from;
    }

    @Override
    public void send(String email, String code, String purpose) {
        var sender = senders.getIfAvailable();
        if (sender == null || from.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Dịch vụ email chưa sẵn sàng");
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("Mã xác minh GoWithBae");
        message.setText("Mã xác minh của bạn: " + code + ". Không chia sẻ mã này với người khác.");
        try { sender.send(message); }
        catch (MailException ex) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chưa gửi được email. Hãy thử lại sau."); }
    }
}
