package com.booking.bookingsystem.service.exception;

import com.booking.bookingsystem.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // Catch our custom 404 Exception
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleResourceNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        ApiErrorResponse error = new ApiErrorResponse(
                Instant.now(),
                HttpStatus.NOT_FOUND.value(),
                HttpStatus.NOT_FOUND.getReasonPhrase(),
                ex.getMessage(),
                request.getRequestURI()
        );
        return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
    }

    // Catch our custom 409 State Conflict Exception
    @ExceptionHandler(SlotUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleSlotUnavailable(SlotUnavailableException ex, HttpServletRequest request) {
        ApiErrorResponse error = new ApiErrorResponse(
                Instant.now(),
                HttpStatus.CONFLICT.value(),
                HttpStatus.CONFLICT.getReasonPhrase(),
                ex.getMessage(),
                request.getRequestURI()
        );
        return new ResponseEntity<>(error, HttpStatus.CONFLICT);
    }

    // Fixed validation typo (Changed .with to .put)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationFailures(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();

        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        Map<String, Object> responseBody = new HashMap<>();
        responseBody.put("timestamp", Instant.now()); // Fixed typo here
        responseBody.put("status", HttpStatus.BAD_REQUEST.value());
        responseBody.put("errors", errors);

        return new ResponseEntity<>(responseBody, HttpStatus.BAD_REQUEST);
    }

//    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
//    public ResponseEntity<ApiErrorResponse> handleOptimisticLock() {
//        return ResponseEntity.status(HttpStatus.CONFLICT)
//                .body();
//    }
}
