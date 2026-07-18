package com.vasundhara.atf.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts unhandled exceptions to structured JSON error responses and logs them.
 *
 * <p>Keeps the client-facing message helpful while ensuring the full stack trace
 * always appears in the application log for debugging.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Already a ResponseStatusException — honour the status and message. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex) {
        if (ex.getStatusCode().value() >= 500) {
            log.error("API error {}: {}", ex.getStatusCode().value(), ex.getReason(), ex);
        } else {
            log.warn("API error {}: {}", ex.getStatusCode().value(), ex.getReason());
        }
        return body(ex.getStatusCode().value(), ex.getReason());
    }

    /** File too large — surface as 413 with a clear message. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUpload(MaxUploadSizeExceededException ex) {
        log.warn("Upload rejected — file exceeds the configured size limit: {}", ex.getMessage());
        return body(HttpStatus.PAYLOAD_TOO_LARGE.value(),
                "Upload rejected: file exceeds the maximum allowed size. "
                + "Check spring.servlet.multipart.max-file-size in application.yml.");
    }

    /** Catch-all for any other unhandled runtime exception. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAll(Exception ex) {
        log.error("Unhandled exception in API layer", ex);
        String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
        return body(HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "An unexpected error occurred: " + msg
                + ". Check the application log for the full stack trace.");
    }

    private ResponseEntity<Map<String, Object>> body(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status",  status);
        body.put("error",   HttpStatus.resolve(status) != null
                ? HttpStatus.resolve(status).getReasonPhrase() : "Error");
        body.put("message", message != null ? message : "No detail available.");
        return ResponseEntity.status(status).body(body);
    }
}
