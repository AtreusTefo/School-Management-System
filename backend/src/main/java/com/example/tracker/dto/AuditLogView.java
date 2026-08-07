package com.example.tracker.dto;

import com.example.tracker.model.AuditLog;

import java.time.Instant;

/**
 * What the audit log page is shown. Never the entity directly, for the same
 * reason every other view in this package exists - a response shape that is
 * allowed to stay stable even if the entity's internals change.
 */
public record AuditLogView(
        Long id,
        String entityName,
        Long entityId,
        String action,
        String performedByUsername,
        String performedByRole,
        Instant performedAt,
        String summary) {

    public static AuditLogView of(AuditLog log) {
        return new AuditLogView(
                log.getId(),
                log.getEntityName(),
                log.getEntityId(),
                log.getAction().name(),
                log.getPerformedByUsername(),
                log.getPerformedByRole() == null ? null : log.getPerformedByRole().name(),
                log.getPerformedAt(),
                log.getSummary());
    }
}
