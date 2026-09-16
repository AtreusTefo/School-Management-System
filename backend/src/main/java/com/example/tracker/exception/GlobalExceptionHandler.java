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
     * 404 NOT FOUND — the client named a subject, class, course or submission
     * that does not exist.
     *
     * Separate from the assignment case above because the two are thrown for
     * different reasons; see ResourceNotFoundException for why keeping them
     * apart is worth one extra class.
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleResourceNotFound(
            ResourceNotFoundException ex, jakarta.servlet.http.HttpServletRequest request) {
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
                "This record was changed by someone else. Reload and try again.",
                request.getRequestURI());
    }

    /**
     * The database refused the operation — but WHICH refusal decides the status.
     *
     * 400 ON A WRITE. A NOT NULL column left empty, a value too long, a CHECK
     * or UNIQUE constraint failed. Reaching here means something got past the
     * application's own validation and the schema is what saved the data; the
     * request genuinely was malformed.
     *
     * 409 ON A DELETE. Nothing is being written, so none of those can be the
     * cause: the only way a DELETE violates a constraint in this schema is a
     * foreign key refusing to orphan a dependent row, because every one of them
     * is ON DELETE NO_ACTION by design. That is not a malformed request - the
     * caller asked a well-formed question about a real record - it is a
     * conflict with the state the record is in, which is exactly what 409 says
     * and what every deliberate blocked-delete in this system already returns.
     *
     * DISCRIMINATING ON THE HTTP METHOD, not on the driver's message. Parsing
     * the SQLState or the text to spot a foreign key would be dialect-specific
     * and would quietly change meaning between SQL Server and the H2 the tests
     * run on. The method is a fact the container already knows.
     *
     * THIS IS A SAFETY NET, NOT THE INTENDED PATH. A delete that can be blocked
     * should be checked in the service first, so the caller gets a sentence
     * naming what is in the way ("Attendance has been recorded for this
     * lesson..."). Landing here means such a check is missing - the answer is
     * at least honest about what happened, but it cannot say what.
     *
     * The driver's own message is still never echoed: it carries table and
     * column names, and that detail belongs in the server log.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrityViolation(
            DataIntegrityViolationException ex, jakarta.servlet.http.HttpServletRequest request) {
        if ("DELETE".equalsIgnoreCase(request.getMethod())) {
            return build(HttpStatus.CONFLICT,
                    "This cannot be deleted because another record still refers to it.",
                    request.getRequestURI());
        }
        return build(HttpStatus.BAD_REQUEST,
                "The record could not be saved because it violates a data constraint.",
                request.getRequestURI());
    }

    /**
     * 413 PAYLOAD TOO LARGE — the uploaded file exceeded the configured limit.
     *
     * Thrown by Spring's multipart parser BEFORE any of our code runs, which is
     * the point: an oversized upload is rejected while it is still arriving,
     * rather than being buffered into memory and then refused. Without this
     * handler it would surface as a bare 500, telling the student nothing about
     * what to do differently.
     *
     * The service checks the size again once it has the bytes, and the schema
     * checks it a third time. That is not redundancy for its own sake - this
     * limit is a container setting, the service's is a business rule, and the
     * column's is a fact about the data. All three can be true at once and only
     * the last one survives a writer that bypasses the application.
     */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleUploadTooLarge(
            org.springframework.web.multipart.MaxUploadSizeExceededException ex,
            jakarta.servlet.http.HttpServletRequest request) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE,
                "That file is too large. The limit is 10 MB.",
                request.getRequestURI());
    }

    /**
     * 403 FORBIDDEN — the caller is signed in but not permitted to do this.
     *
     * Distinct from 401, which means "I do not know who you are". Returning 401
     * here would tell a signed-in user to log in again, which would not help and
     * would be untrue.
     *
     * Note that some authority failures deliberately surface as 404 instead —
     * see AssignmentService.requireVisible. Where merely confirming that a row
     * exists would leak information, "not found" is the safer honest answer.
     */
    @ExceptionHandler(com.example.tracker.exception.AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(
            com.example.tracker.exception.AccessDeniedException ex,
            jakarta.servlet.http.HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, ex.getMessage(), request.getRequestURI());
    }

    /**
     * 401 UNAUTHORIZED — the credentials offered at sign-in were not valid.
     *
     * The message never says WHICH part was wrong. Distinguishing "no such user"
     * from "wrong password" would let an attacker enumerate valid usernames,
     * splitting one hard guess into two easy ones.
     */
    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthenticationFailure(
            org.springframework.security.core.AuthenticationException ex,
            jakarta.servlet.http.HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED,
                "Invalid username or password.", request.getRequestURI());
    }

    /**
     * 429 TOO MANY REQUESTS — sign-in was refused before the credentials were
     * looked at, because too many recent attempts failed.
     *
     * A SEPARATE STATUS FROM 401, DELIBERATELY, AND THE ONE PLACE THIS SYSTEM
     * TELLS A CALLER SOMETHING IT OTHERWISE WOULD NOT. Everything around it is
     * built to answer identically whatever was tried; this answers differently.
     * That is safe only because of how LoginThrottleService counts: a failure
     * is recorded for ANY attempted username, so an invented one reaches 429
     * exactly as readily as a real one and the status says nothing about
     * whether the account exists. If that ever changes to count only real
     * accounts, this handler becomes an enumeration oracle.
     *
     * 401 would have been the alternative - indistinguishable, but it would
     * also have told a legitimate user who mistyped that their password was
     * wrong, and left them retrying against a wall with no explanation.
     *
     * No Retry-After header: the message is deliberately vague about when the
     * window reopens, and a header giving the exact second would hand an
     * attacker a timer to pace against.
     */
    @ExceptionHandler(TooManyAttemptsException.class)
    public ResponseEntity<ApiError> handleTooManyAttempts(
            TooManyAttemptsException ex,
            jakarta.servlet.http.HttpServletRequest request) {
        return build(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), request.getRequestURI());
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
