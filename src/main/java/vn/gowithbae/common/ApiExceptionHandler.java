package vn.gowithbae.common;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Mọi lỗi trả application/problem+json theo schema Problem của contract. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = ex.getBody();
        body.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
                .map(e -> Map.of("field", e.getField(), "message", String.valueOf(e.getDefaultMessage())))
                .toList());
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = ex.getBody();
        List<Map<String, String>> errors = ex.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream().map(e -> Map.of(
                        "field", String.valueOf(r.getMethodParameter().getParameterName()),
                        "message", String.valueOf(e.getDefaultMessage()))))
                .toList();
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    /**
     * Ràng buộc tham số (@Size, @Max, @Pattern…) trên controller sinh từ contract được kiểm tra bằng
     * MethodValidationInterceptor và ném ConstraintViolationException; trả 400 thay vì rơi vào lỗi 500.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail constraintViolation(ConstraintViolationException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Dữ liệu không hợp lệ");
        pd.setProperty("errors", ex.getConstraintViolations().stream()
                .map(v -> Map.of("field", lastNode(v.getPropertyPath()), "message", String.valueOf(v.getMessage())))
                .toList());
        return pd;
    }

    private static String lastNode(Path path) {
        String name = "";
        for (Path.Node node : path) {
            name = String.valueOf(node.getName());
        }
        return name;
    }

    /** Lỗi không lường trước: không trả stack/bí mật, chỉ traceId để tra log (RULE-27). */
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception ex) {
        String traceId = UUID.randomUUID().toString();
        log.error("Unhandled error traceId={}", traceId, ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Đã có lỗi xảy ra");
        pd.setProperty("traceId", traceId);
        return pd;
    }
}
