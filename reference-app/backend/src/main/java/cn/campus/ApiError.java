package cn.campus;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;

public class ApiError extends RuntimeException {
    final int status;

    ApiError(String message) {
        this(400, message);
    }

    ApiError(int status, String message) {
        super(message);
        this.status = status;
    }
}

@RestControllerAdvice
class ErrorAdvice {
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<?> missing(Exception e) {
        return ResponseEntity.status(404).body(Map.of("message", "资源不存在。"));
    }

    @ExceptionHandler({ org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class })
    ResponseEntity<?> invalid(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("message", "请求参数缺失或格式错误。"));
    }

    @ExceptionHandler(ApiError.class)
    ResponseEntity<?> business(ApiError e) {
        return ResponseEntity.status(e.status).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> conflict(Exception e) {
        return ResponseEntity.status(409).body(Map.of("message", "记录冲突或重复提交，请刷新查看已有结果。"));
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<?> large(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("message", "图片最大为 5MB。"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> other(Exception e) {
        org.slf4j.LoggerFactory.getLogger(ErrorAdvice.class).error("Request failed", e);
        return ResponseEntity.status(500).body(Map.of("message", "服务器处理失败，请保留操作并重试查询。"));
    }
}
