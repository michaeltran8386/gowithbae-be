package vn.gowithbae;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

import vn.gowithbae.upload.FileStorage;
import vn.gowithbae.upload.SupabaseFileStorage;

/** app.files.storage=supabase thì toàn ứng dụng dùng Supabase Storage (không còn bean lưu trên đĩa). Cần Docker. */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"app.jwt.secret=test-secret-test-secret-test-secret-123", "DB_URL=", "DB_USERNAME=", "DB_PASSWORD=",
        "app.files.storage=supabase", "app.files.supabase.url=https://example.supabase.co",
        "app.files.supabase.service-key=sb_secret_test"})
class StorageSelectionTest {

    @Autowired FileStorage storage;
    @Autowired org.springframework.context.ApplicationContext context;

    @Test
    void supabaseStorageIsTheOnlyFileStorage() {
        assertThat(storage).isInstanceOf(SupabaseFileStorage.class);
        assertThat(context.getBeansOfType(FileStorage.class)).hasSize(1);
    }
}
