package vn.gowithbae.auth;

/** Adapter SMTP trong runtime; test thay bằng hộp thư in-memory, không trả/log OTP qua API. */
public interface OtpDelivery {
    void send(String email, String code, String purpose);
}
