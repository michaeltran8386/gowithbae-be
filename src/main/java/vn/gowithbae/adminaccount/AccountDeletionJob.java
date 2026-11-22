package vn.gowithbae.adminaccount;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tự xóa (ẩn danh hóa) tài khoản đã yêu cầu xóa quá {@code app.accounts.deletion-grace-days} ngày (mặc định 30)
 * mà quản trị viên không khôi phục. Chạy định kỳ và một lần ngay sau khởi động, vì máy chủ miễn phí (Render) hay ngủ.
 * Mỗi tài khoản một transaction: lỗi ở một tài khoản không chặn các tài khoản khác.
 */
@Component
public class AccountDeletionJob {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionJob.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AccountEraser eraser;
    private final int graceDays;

    public AccountDeletionJob(JdbcClient jdbc, TransactionTemplate tx, AccountEraser eraser,
            @Value("${app.accounts.deletion-grace-days:30}") int graceDays) {
        if (graceDays < 1) throw new IllegalStateException("app.accounts.deletion-grace-days phải ≥ 1");
        this.jdbc = jdbc;
        this.tx = tx;
        this.eraser = eraser;
        this.graceDays = graceDays;
    }

    @Scheduled(initialDelayString = "${app.accounts.deletion-check-initial-ms:120000}",
            fixedDelayString = "${app.accounts.deletion-check-ms:21600000}")
    public void run() {
        int erased = purgeDue();
        if (erased > 0) log.info("Đã tự xóa {} tài khoản quá {} ngày sau yêu cầu xóa", erased, graceDays);
    }

    /** @return số tài khoản đã xóa. */
    public int purgeDue() {
        List<UUID> due = jdbc.sql("""
                SELECT id FROM app_user
                WHERE status = 'DELETION_REQUESTED' AND deletion_requested_at < now() - make_interval(days => :days)
                ORDER BY deletion_requested_at
                """).param("days", graceDays).query(UUID.class).list();
        int erased = 0;
        for (UUID id : due) {
            try {
                Boolean done = tx.execute(status -> {
                    // Kiểm tra lại trong transaction: quản trị viên có thể vừa khôi phục.
                    boolean stillDue = jdbc.sql("""
                            SELECT EXISTS (SELECT 1 FROM app_user WHERE id = :id AND status = 'DELETION_REQUESTED'
                                           AND deletion_requested_at < now() - make_interval(days => :days) FOR UPDATE)
                            """).param("id", id).param("days", graceDays).query(Boolean.class).single();
                    if (!stillDue) return false;
                    eraser.erase(id, null, "Tự xóa sau " + graceDays + " ngày theo yêu cầu xóa của người dùng");
                    return true;
                });
                if (Boolean.TRUE.equals(done)) erased++;
            } catch (RuntimeException e) {
                log.warn("Không tự xóa được tài khoản {}: {}", id, e.toString());
            }
        }
        return erased;
    }
}
