package vn.gowithbae.auth;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import vn.gowithbae.platform.AuditService;

/** Lệnh vận hành tường minh, không phải endpoint đăng ký MFA từ phiên email-only. */
@Component
@ConditionalOnProperty("app.mfa.enroll-user")
public class MfaProvisioner implements ApplicationRunner {
    private final UUID user;
    private final StaffMfa mfa;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final ConfigurableApplicationContext context;

    public MfaProvisioner(@Value("${app.mfa.enroll-user}") UUID user,StaffMfa mfa,AuditService audit,
            TransactionTemplate tx,ConfigurableApplicationContext context){
        this.user=user;this.mfa=mfa;this.audit=audit;this.tx=tx;this.context=context;
    }

    @Override public void run(ApplicationArguments arguments){
        var console=System.console();
        if(console==null)throw new IllegalStateException("Provisioning MFA cần terminal tương tác; không truyền secret qua command line");
        char[] secret=console.readPassword("TOTP secret Base32 (không hiển thị): ");
        if(secret==null)throw new IllegalArgumentException("Chưa nhập MFA secret");
        try {
            tx.executeWithoutResult(status->{
                mfa.enroll(user,new String(secret));
                audit.log(user,"MFA_ENROLLED","app_user",user.toString(),null,Map.of("mfaEnabled",true),"Trusted CLI provisioning");
            });
        } finally {Arrays.fill(secret,'\0');}
        console.printf("Đã lưu MFA và thu hồi các phiên cũ của tài khoản.%n");
        SpringApplication.exit(context);
    }
}
