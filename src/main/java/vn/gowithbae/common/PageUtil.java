package vn.gowithbae.common;

import vn.gowithbae.api.model.PageMeta;

/** Chuẩn hóa phân trang cho các API quản trị/danh sách. */
public final class PageUtil {

    private PageUtil() {}

    public static int sanitizePage(Integer page) {
        return page == null || page < 0 ? 0 : page;
    }

    public static int sanitizeSize(Integer size) {
        if (size == null) {
            return 20;
        }
        return Math.min(Math.max(size, 1), 100);
    }

    public static PageMeta buildMeta(int page, int size, long totalElements) {
        int totalPages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new PageMeta(page, size, totalElements, totalPages);
    }
}
