package vn.gowithbae.platform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SecretHash {
    private final byte[] key;
    public SecretHash(@Value("${app.jwt.secret}") String secret) { this.key = secret.getBytes(StandardCharsets.UTF_8); }

    public String digest(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException ex) { throw new IllegalStateException("Không khởi tạo được HMAC", ex); }
    }

    public boolean matches(String value, String digest) {
        return MessageDigest.isEqual(digest(value).getBytes(StandardCharsets.US_ASCII), digest.getBytes(StandardCharsets.US_ASCII));
    }
}
