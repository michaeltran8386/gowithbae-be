package vn.gowithbae.common.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Tạo lỗi problem+json thống nhất; {@code ApiExceptionHandler} trả nguyên ProblemDetail.
 * Lỗi nghiệp vụ 409 mang {@code code} là mã quy tắc (DEVELOPMENT.md §4 Bước 4).
 */
public final class Problems {

    private Problems() {
    }

    /** Tài nguyên không tồn tại hoặc không thuộc quyền: luôn 404 để không lộ sự tồn tại (FR-22). */
    public static ErrorResponseException notFound() {
        return of(HttpStatus.NOT_FOUND, "Không tìm thấy");
    }

    public static ErrorResponseException badRequest(String detail) {
        return of(HttpStatus.BAD_REQUEST, detail);
    }

    /** 400 kèm {@code errors[]} theo trường, cùng dạng với lỗi validation của Spring. */
    public static ErrorResponseException badRequest(String detail, List<Map<String, String>> errors) {
        ErrorResponseException ex = of(HttpStatus.BAD_REQUEST, detail);
        ex.getBody().setProperty("errors", errors);
        return ex;
    }

    public static ErrorResponseException forbidden(String detail) {
        return of(HttpStatus.FORBIDDEN, detail);
    }

    public static ErrorResponseException conflict(String code, String detail) {
        ErrorResponseException ex = of(HttpStatus.CONFLICT, detail);
        ex.getBody().setProperty("code", code);
        return ex;
    }

    public static Map<String, String> fieldError(String field, String message) {
        return Map.of("field", field, "message", message);
    }

    private static ErrorResponseException of(HttpStatus status, String detail) {
        return new ErrorResponseException(status, ProblemDetail.forStatusAndDetail(status, detail), null);
    }
}
