package com.example.tracker.dto;

/**
 * Somebody this account is ALLOWED to write to, for the compose form.
 *
 * The list is produced by MessagingService from the same rule that refuses a
 * send - see requireMayWriteTo - rather than from a second, parallel
 * definition of who is reachable. That matters: if the two were written
 * separately, the form would eventually offer a name the send path refuses,
 * which is a worse experience than not offering it at all.
 */
public record CorrespondentView(
        Long id,
        String username,
        String role,
        String fullName,
        String context) {
}
