package com.example.tracker.exception;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.stream.Collectors;

/**
 * GLOBAL EXCEPTION HANDLER (part of the PRESENTATION layer)
 * --------------------------------------------------------
 * The service layer signals problems by THROWING exceptions — that is correct,
 * because the service must not know anything about HTTP. But somebody has to
 * translate those exceptions into HTTP status codes, or Spring falls back to a
 * blanket "500 Internal Server Error" for every one of them.
 *
 * That's exactly what was happening before this class existed:
 *   - blank title            -> 500  (should be 400 Bad Request)
 *   - unknown assignment id  -> 500  (should be 404 Not Found)
 *   - already submitted      -> 500  (should be 409 Conflict)
 *
 * 500 means "the server broke". None of the above are the server breaking —
 * they're the CLIENT being told it asked for something invalid. Returning the
 * right code (and an actual message) is what lets the Angular app show the user
 * something useful instead of failing silently.
 *
 * @RestControllerAdvice = "apply these handlers to every @RestController".
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * The JSON shape we send for every error. Keeping ONE shape means the
     * frontend only ever has to parse one thing.
     *
     * A "record" is a compact Java class that is just data — the compiler writes
     * the constructor and getters for us. Jackson turns it into JSON like:
     *   {"timestamp":"...","status":404,"error":"Not Found","message":"...","path":"..."}
     */
    public record ApiError(
            String timestamp,
            int status,
            String error,
            String message,
            String path) {
    }

    /** Small helper so each handler below stays a single readable line. */
    private ResponseEntity<ApiError> build(HttpStatus status, String message, String path) {
        ApiError body = new ApiError(
                Instant.now().toString(),
                status.value(),
                status.getReasonPhrase(),
                message,
                path);
        return ResponseEntity.status(status).body(body);
    }

    /**
     * 404 NOT FOUND — the client asked for an assignment that doesn't exist.
     * Example: PUT /api/assignments/999/submit
     */
    @ExceptionHandler(AssignmentNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(
            AssignmentNotFoundException ex, jakarta.servlet.http.HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request.getRequestURI());
    }

    /**
     * 409 CONFLICT — the request made sense, but it clashes with the CURRENT
     * state of the data. Example: submitting an assignment that is already
     * SUBMITTED. (409 is the standard code for "you can't do that *right now*".)
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleConflict(
            IllegalStateException ex, jakarta.servlet.http.HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request.getRequestURI());
    }

    /**
     * 400 BAD REQUEST — the service rejected the input on a business rule.
     * Example: creating an assignment with a blank title.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleBadRequest(
            IllegalArgumentException ex, jakarta.servlet.http.HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request.getRequestURI());
    }

    /**
     * 409 CONFLICT — somebody else changed this row while we were working on it.
     *
     * The entity carries a @Version column, so Hibernate adds "AND version = ?"
     * to every UPDATE. When a competing transaction commits first, this one
     * matches zero rows and fails here instead of silently overwriting the other
     * person's change.
     *
     * 409 is right for the same reason as the already-submitted case: the
     * request was well formed, but it no longer fits the current state. Retrying
     * against fresh data is the correct response, so we say so.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleConcurrentModification(
            OptimisticLockingFailureException ex, jakarta.servlet.http.HttpServletRequest request) {
        return build(HttpStatus.CONFLICT,
                "This assignment was changed by someone else. Reload and try again.",
                request.getRequestURI());
    }

    /**
     * 400 BAD REQUEST — the database refused the row: a NOT NULL column was
     * empty, a value was too long, or a CHECK/UNIQUE constraint failed.
     *
     * Reaching this handler means something got past the application's own
     * validation, so the constraint in the schema is what saved the data. We
     * deliberately do NOT echo the driver's message back to the client, because
     * it exposes table and column names; the detail belongs in the server log.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrityViolation(
            DataIntegrityViolationException ex, jakarta.servlet.http.HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST,
                "The assignment could not be saved because it violates a data constraint.",
                request.getRequestURI());
    }

    /**
     * 400 BAD REQUEST — thrown by Spring when a @Valid @RequestBody fails its
     * annotations (e.g. @NotBlank on the title). We flatten the validation
     * failures into one readable sentence, e.g. "title: Title must not be blank".
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException ex, jakarta.servlet.http.HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, message, request.getRequestURI());
    }
}
