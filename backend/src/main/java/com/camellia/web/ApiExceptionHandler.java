package com.camellia.web;

import com.camellia.integration.gemini.GeminiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

/** Standardized error envelope: {status, code, message, timestamp}. Internal details are logged, not returned. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    public record ApiError(int status, String code, String message, LocalDateTime timestamp) {}

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> api(ApiException e) {
        return build(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler(GeminiException.class)
    public ResponseEntity<ApiError> gemini(GeminiException e) {
        log.warn("Gemini request failed: {}", e.getMessage());
        return build(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage()).collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", msg);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
    public ResponseEntity<ApiError> badInput(Exception e) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Invalid request: " + e.getMessage());
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> database(DataAccessException e) {
        log.error("Database error", e);
        return build(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_ERROR",
                "The database request failed. Check the backend log and GET /api/integrations/status.");
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> tooLarge(Exception e) {
        return build(HttpStatus.CONTENT_TOO_LARGE, "DOCUMENT_TOO_LARGE", "The file is larger than the upload limit.");
    }

    @ExceptionHandler({org.springframework.web.multipart.MultipartException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    public ResponseEntity<ApiError> badUpload(Exception e) {
        return build(HttpStatus.BAD_REQUEST, "BAD_UPLOAD", "Choose a PDF or Word document to upload.");
    }

    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> missingParameter(org.springframework.web.bind.MissingServletRequestParameterException e) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Missing request parameter '" + e.getParameterName() + "'.");
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ApiError> noRoute(Exception e) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "No such endpoint.");
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> method(Exception e) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("Unexpected error", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error. See the backend log.");
    }

    private static ResponseEntity<ApiError> build(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(status.value(), code, message, LocalDateTime.now()));
    }
}
