package vn.gowithbae.shared;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.lang.Nullable;

import vn.gowithbae.api.model.StoredFile;

/** Hợp đồng kiểm tra quyền tệp và ánh xạ stored_file theo TEAM_SPLIT (cung cấp bởi Người A). */
public interface FileService {

    void validateAttachments(
            @Nullable UUID actorUserId,
            @Nullable List<UUID> fileIds,
            String expectedPurpose,
            boolean requireOwnedByUser);

    Map<UUID, StoredFile> getFiles(Collection<UUID> fileIds);
}
