package deposit_early_termination_channel.exception;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import java.time.LocalDateTime;
import java.util.Map;

@RestControllerAdvice
public class ApprovalExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> business(BusinessException ex, HttpServletRequest req) {
        return ResponseEntity.status(ex.getStatus()).body(error(ex.getCode(), ex.getMessage(), req));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<Map<String, Object>> badRequest(Exception ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(error("INVALID_REQUEST", "요청 형식 또는 필수 값이 올바르지 않습니다.", req));
    }

    private Map<String, Object> error(String code, String message, HttpServletRequest req) {
        return Map.of("code", code, "message", message,
            "path", req.getRequestURI(), "timestamp", LocalDateTime.now().toString());
    }
}
