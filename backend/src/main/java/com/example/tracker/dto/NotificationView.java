package com.example.tracker.dto;

import com.example.tracker.model.Notification;

import java.time.Instant;

/**
 * One notification, as the API publishes it. See SubjectView for why every
 * endpoint returns a record rather than the entity.
 *
 * subjectType/subjectId are published so the interface can offer a link to
 * whatever this is about. They are NOT a foreign key on the way in either -
 * see Notification's class comment - so the client must treat a subject that
 * no longer exists as a link to nothing rather than an error. title and body
 * are the snapshot, and remain readable whether the subject is there or not.
 */
public record NotificationView(
        Long id,
        String type,
        String title,
        String body,
        String subjectType,
        Long subjectId,
        Instant createdAt,
        boolean read) {

    public static NotificationView of(Notification n) {
        return new NotificationView(
                n.getId(),
                n.getType().name(),
                n.getTitle(),
                n.getBody(),
                n.getSubjectType(),
                n.getSubjectId(),
                n.getCreatedAt(),
                n.isRead());
    }
}
