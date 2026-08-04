package com.example.tracker.exception;

/**
 * "There is no such subject / class / course / submission."
 *
 * A sibling of AssignmentNotFoundException rather than a replacement for it. Two
 * exception types for the same status code looks redundant until you notice they
 * are thrown for different reasons: the assignment one predates the rest of the
 * model and is thrown in one place that also doubles as an authority guard,
 * where "not found" is deliberately returned instead of "forbidden".
 *
 * Collapsing them would make that deliberate 404 indistinguishable from an
 * ordinary missing row, and the next person to tidy the code would not know
 * which of the two behaviours they were changing.
 *
 * The message names the KIND of thing and its id, so a client can tell "no such
 * class" from "no such subject" without guessing from the URL.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String what, Object id) {
        super("No " + what + " found with id = " + id);
    }

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
