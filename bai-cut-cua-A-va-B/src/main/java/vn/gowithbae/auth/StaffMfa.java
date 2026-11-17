package vn.gowithbae.auth;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StaffMfa {
    private final JdbcClient jdbc;
    private final SecretKeySpec key;
    private final SecureRandom random=new SecureRandom();

    public StaffMfa(JdbcClient jdbc,@Value("${app.auth.mfa-key:${app.jwt.secret}}") String key) {
        if(key.getBytes(StandardCharsets.UTF_8).length<32) throw new IllegalArgumentException("MFA key phải ít nhất 32 byte");
        this.jdbc=jdbc;
        try { this.key=new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(("staff-mfa:"+key).getBytes(StandardCharsets.UTF_8)),"AES"); }
        catch(GeneralSecurityException ex){throw new IllegalStateException("Không khởi tạo được mã hóa MFA",ex);}
    }

    /** Chỉ dùng trong provisioning tin cậy; không expose thành endpoint tự cấp MFA. */
    @Transactional
    public void enroll(UUID userId,String secret) {
        decode(secret);
        boolean staff=jdbc.sql("SELECT EXISTS(SELECT 1 FROM user_role WHERE user_id=:id AND role_code<>'MEMBER')")
                .param("id",userId).query(Boolean.class).single();
        if(!staff)throw new IllegalArgumentException("Chỉ enroll MFA cho nhân viên đã được gán vai trò");
        jdbc.sql("""
                INSERT INTO staff_mfa(user_id,secret_ciphertext) VALUES (:id,:secret)
                ON CONFLICT (user_id) DO UPDATE SET secret_ciphertext=EXCLUDED.secret_ciphertext,last_step=-1,enrolled_at=now()
                """).param("id",userId).param("secret",encrypt(secret)).update();
        jdbc.sql("UPDATE access_session SET revoked_at=now() WHERE user_id=:id AND revoked_at IS NULL").param("id",userId).update();
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public boolean verify(UUID userId,String supplied) {
        if(supplied==null || !supplied.matches("[0-9]{6}"))return false;
        record Factor(String ciphertext,long lastStep){}
        Factor factor=jdbc.sql("SELECT secret_ciphertext,last_step FROM staff_mfa WHERE user_id=:id FOR UPDATE")
                .param("id",userId).query((rs,n)->new Factor(rs.getString(1),rs.getLong(2))).optional().orElse(null);
        if(factor==null)return false;
        String secret=decrypt(factor.ciphertext());
        long step=Instant.now().getEpochSecond()/30;
        for(long candidate=step-1;candidate<=step+1;candidate++){
            if(candidate>factor.lastStep() && MessageDigest.isEqual(code(secret,candidate).getBytes(StandardCharsets.US_ASCII),supplied.getBytes(StandardCharsets.US_ASCII))){
                jdbc.sql("UPDATE staff_mfa SET last_step=:step WHERE user_id=:id").param("step",candidate).param("id",userId).update();
                return true;
            }
        }
        return false;
    }

    public static String code(String secret,long step) {
        try {
            Mac mac=Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decode(secret),"HmacSHA1"));
            byte[] digest=mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset=digest[digest.length-1]&15;
            int value=ByteBuffer.wrap(digest,offset,4).getInt()&0x7fffffff;
            return String.format(Locale.ROOT,"%06d",value%1_000_000);
        }catch(GeneralSecurityException ex){throw new IllegalStateException("Không tính được TOTP",ex);}
    }

    private static byte[] decode(String secret) {
        if(secret==null || !secret.matches("[A-Z2-7]{32,128}"))throw new IllegalArgumentException("MFA secret phải là Base32 không padding, ít nhất 160 bit");
        String alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        var bytes=new java.io.ByteArrayOutputStream();
        int buffer=0,bits=0;
        for(char c:secret.toCharArray()){
            buffer=(buffer<<5)|alphabet.indexOf(c);bits+=5;
            if(bits>=8){bits-=8;bytes.write((buffer>>bits)&255);buffer&=(1<<bits)-1;}
        }
        if(buffer!=0 || bytes.size()<20)throw new IllegalArgumentException("MFA secret không hợp lệ");
        return bytes.toByteArray();
    }

    private String encrypt(String secret) {
        try {
            byte[] iv=new byte[12];random.nextBytes(iv);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,iv));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(12+secret.length()+16).put(iv)
                    .put(cipher.doFinal(secret.getBytes(StandardCharsets.US_ASCII))).array());
        }catch(GeneralSecurityException ex){throw new IllegalStateException("Không mã hóa được MFA",ex);}
    }

    private String decrypt(String ciphertext) {
        try {
            byte[] bytes=Base64.getDecoder().decode(ciphertext);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOf(bytes,12)));
            return new String(cipher.doFinal(bytes,12,bytes.length-12),StandardCharsets.US_ASCII);
        }catch(GeneralSecurityException|IllegalArgumentException ex){throw new IllegalStateException("Không đọc được cấu hình MFA");}
    }
}
