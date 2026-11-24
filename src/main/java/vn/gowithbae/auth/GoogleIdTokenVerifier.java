package vn.gowithbae.auth;

/** Xác minh Google ID token (CR-002). Tách interface để test thay bằng bản giả, không gọi mạng. */
public interface GoogleIdTokenVerifier {
    record GoogleIdentity(String subject, String email, boolean emailVerified, String name) {}

    /** Trả danh tính đã xác minh, hoặc ném {@link InvalidGoogleTokenException} nếu token sai/hết hạn/sai audience. */
    GoogleIdentity verify(String idToken);

    class InvalidGoogleTokenException extends RuntimeException {
        public InvalidGoogleTokenException(String message, Throwable cause) { super(message, cause); }
    }
}
