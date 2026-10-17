package com.booking.bookingsystem.service.exception;

import com.booking.bookingsystem.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.hibernate.exception.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(InvalidClinicAccessRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidClinicRequest(HttpServletRequest request) {
        return ResponseEntity.badRequest().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new ApiErrorResponse(Instant.now(), 400, "Bad Request", "Clinic ID is required.", request.getRequestURI()));
    }

    @ExceptionHandler(ClinicAccessRequestConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleClinicRequestConflict(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new ApiErrorResponse(Instant.now(), 409, "Conflict",
                        "A pending request or existing doctor assignment prevents this request.", request.getRequestURI()));
    }

    @ExceptionHandler(DoctorProfileAlreadyExistsException.class)
    public ResponseEntity<ApiErrorResponse> handleDoctorProfileAlreadyExists(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new ApiErrorResponse(Instant.now(), 409, "Conflict",
                        "A Doctor profile already exists for this account.", request.getRequestURI()));
    }

    @ExceptionHandler(InvalidLoginSessionException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidLoginSession(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new ApiErrorResponse(Instant.now(), 401, "Unauthorized", "Authentication required.", request.getRequestURI()));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidCredentials(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new ApiErrorResponse(Instant.now(), 401, "Unauthorized", "Invalid email or password.",
                        request.getRequestURI()));
    }

    @ExceptionHandler(InvalidRegistrationException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidRegistration(
            InvalidRegistrationException ex, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new ApiErrorResponse(
                Instant.now(), 400, "Bad Request", ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(AccountAlreadyExistsException.class)
    public ResponseEntity<ApiErrorResponse> handleAccountAlreadyExists(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiErrorResponse(
                Instant.now(), 409, "Conflict", "An account with this email already exists.",
                request.getRequestURI()));
    }

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

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleOptimisticLock(HttpServletRequest request) {
        ApiErrorResponse error = new ApiErrorResponse(
                Instant.now(),
                HttpStatus.CONFLICT.value(),
                HttpStatus.CONFLICT.getReasonPhrase(),
                "The reservation changed while processing your request. Check availability and try again.",
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrity(
            DataIntegrityViolationException ex, HttpServletRequest request) {
        Set<String> reservationConstraints = Set.of(
                "uq_hold_active_slot", "uq_appointment_confirmed_slot");
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && "23505".equals(violation.getSQLState())
                    && violation.getConstraintName() != null
                    && reservationConstraints.contains(violation.getConstraintName())) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiErrorResponse(
                        Instant.now(), 409, "Conflict",
                        "The slot already has an active reservation or confirmed appointment.",
                        request.getRequestURI()));
            }
        }
        log.error("Unexpected database integrity failure at {}", request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiErrorResponse(
                Instant.now(), 500, "Internal Server Error", "Unable to complete the request.",
                request.getRequestURI()));
    }
}
