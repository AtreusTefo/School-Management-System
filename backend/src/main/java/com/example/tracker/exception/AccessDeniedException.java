package com.example.tracker.exception;

/**
 * Thrown when the caller is authenticated but not permitted to do this.
 *
 * WHY OUR OWN TYPE RATHER THAN SPRING SECURITY'S
 * The service layer must not depend on the web or security framework - that is
 * the same rule that keeps HTTP types out of it. Throwing our own exception
 * keeps the business layer free of framework imports, and
 * GlobalExceptionHandler maps it to 403 at the edge.
 *
 * Distinct from AssignmentNotFoundException on purpose:
 *   403 - "you may not do that"      (the thing exists; you lack authority)
 *   404 - "there is no such thing"   (used deliberately when even revealing
 *                                     existence would leak information)
 */
public class AccessDeniedException extends RuntimeException {

    public AccessDeniedException(String message) {
        super(message);
    }
}
