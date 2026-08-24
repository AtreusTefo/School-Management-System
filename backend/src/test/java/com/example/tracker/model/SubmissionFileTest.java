package com.example.tracker.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * The constructor is the only place a SubmissionFile is ever built - see its
 * own class comment on why filename/contentType/content are rejected here
 * rather than trusted. sha256 belongs to that same set (NOT NULL at the
 * database, @NotBlank on the field) and is checked the same way.
 *
 * Plain JUnit, no Spring context: this is a single constructor's invariant,
 * not a database round trip.
 */
class SubmissionFileTest {

    private static final byte[] SOME_BYTES = {1, 2, 3};
    private static final String SOME_HASH = "a".repeat(64);

    @Test
    @DisplayName("a null checksum is refused, the same as a null filename/contentType/content")
    void nullChecksumIsRefused() {
        assertThatThrownBy(() -> new SubmissionFile(
                null, "answer.pdf", "application/pdf", SOME_BYTES, null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("checksum");
    }

    @Test
    @DisplayName("a real checksum is accepted")
    void realChecksumIsAccepted() {
        assertDoesNotThrow(() -> new SubmissionFile(
                null, "answer.pdf", "application/pdf", SOME_BYTES, SOME_HASH, Instant.now()));
    }
}
