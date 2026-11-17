package vn.gowithbae.upload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Private local storage; never configure this directory as a static resource. */
@Component
public class LocalFileStorage {
    private final Path root;

    public LocalFileStorage(@Value("${app.files.root:.local-data/uploads}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    private Path path(String key) throws IOException {
        try {
            if (!UUID.fromString(key).toString().equals(key)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid storage key");
        }
        for (Path parent = root; parent != null; parent = parent.getParent()) {
            if (Files.isSymbolicLink(parent)) throw new IOException("Invalid storage root");
        }
        return root.resolve(key);
    }

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

    public InputStream open(String key) throws IOException {
        return Files.newInputStream(path(key), LinkOption.NOFOLLOW_LINKS);
    }

    public void delete(String key) throws IOException {
        Files.deleteIfExists(path(key));
    }
}
