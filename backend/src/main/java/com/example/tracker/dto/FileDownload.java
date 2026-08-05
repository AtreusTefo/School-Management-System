package com.example.tracker.dto;

import org.springframework.lang.NonNull;

/**
 * A document on its way out of the system.
 *
 * A plain carrier, deliberately free of any HTTP type. The service produces one
 * of these and the controller decides what headers to put around it - which is
 * the layering rule doing real work rather than being recited: the rule about
 * who may download somebody's coursework is a business rule, and it stays
 * testable without a web server because nothing here mentions one.
 *
 * WHY THE COMPONENTS ARE @NonNull
 * -------------------------------
 * Not to silence a warning - to state a guarantee that is already true and let
 * the compiler carry it.
 *
 * Every one of these comes from a SubmissionFile whose backing columns are
 * NOT NULL, and SubmissionService.download refuses with 404 rather than
 * returning a record with a missing field. Saying so here means the controller
 * can hand them straight to MediaType.parseMediaType and ByteArrayResource -
 * both of which require non-null - without a cast, a check, or a suppression.
 *
 * Leaving it unstated was not neutral. It produced "needs unchecked conversion"
 * at every call site, and a warning that appears wherever a type is USED, rather
 * than where the missing information is, is a warning people learn to scroll
 * past.
 */
public record FileDownload(
        @NonNull String filename,
        @NonNull String contentType,
        @NonNull byte[] content) {
}
