package vn.gowithbae.content;

import java.util.concurrent.TimeUnit;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import vn.gowithbae.api.SiteContentApiDelegate;
import vn.gowithbae.api.model.SiteContent;

/** Nội dung trang tĩnh công khai; cache ngắn để sửa trong admin hiện ra nhanh. */
@Service
public class SiteContentDelegate implements SiteContentApiDelegate {

    private final SiteContentService content;

    public SiteContentDelegate(SiteContentService content) {
        this.content = content;
    }

    @Override
    public ResponseEntity<SiteContent> getSiteContent() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS).cachePublic()).body(content.current());
    }
}
