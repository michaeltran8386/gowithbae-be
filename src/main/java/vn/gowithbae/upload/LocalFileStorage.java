package vn.gowithbae.upload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Private local storage; never configure this directory as a static resource. Mặc định khi không đặt app.files.storage. */
@Component
@ConditionalOnProperty(name = "app.files.storage", havingValue = "local", matchIfMissing = true)
public class LocalFileStorage implements FileStorage {
    private final Path root;

    public LocalFileStorage(@Value("${app.files.root:.local-data/uploads}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    private Path path(String key) throws IOException {
        FileStorage.requireValidKey(key);
        for (Path parent = root; parent != null; parent = parent.getParent()) {
            if (Files.isSymbolicLink(parent)) throw new IOException("Invalid storage root");
        }
        return root.resolve(key);
    }

    @Override
    public void write(String key, byte[] bytes) throws IOException {
        Files.createDirectories(root);
        Path target = path(key);
        if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
            Files.createFile(target, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
            // Windows inherits the configured private directory ACL; operators must restrict that ACL.
            Files.createFile(target);
        }
        try {
            Files.write(target, bytes, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException ex) {
            try { Files.deleteIfExists(target); } catch (IOException cleanup) { ex.addSuppressed(cleanup); }
            throw ex;
        }
    }

    @Override
    public InputStream open(String key) throws IOException {
        return Files.newInputStream(path(key), LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public void delete(String key) throws IOException {
        Files.deleteIfExists(path(key));
    }
}
