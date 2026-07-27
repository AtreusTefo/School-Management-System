package com.example.tracker.exception;

/**
 * Thrown by the service when an assignment id doesn't exist.
 *
 * WHY A DEDICATED CLASS?
 * ----------------------
 * The service previously threw IllegalArgumentException for BOTH "the title is
 * blank" and "no such assignment". Those are different problems and deserve
 * different HTTP responses (400 vs 404) — but if both are the same Java type,
 * the exception handler has no way to tell them apart.
 *
 * Giving "not found" its own type makes the distinction explicit, so
 * GlobalExceptionHandler can map each one to the right status code.
 */
public class AssignmentNotFoundException extends RuntimeException {

    public AssignmentNotFoundException(Long id) {
        super("No assignment found with id = " + id);
    }
}
