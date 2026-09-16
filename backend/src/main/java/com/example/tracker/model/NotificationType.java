package com.example.tracker.model;

/**
 * What a notification is about.
 *
 * Stored with {@code EnumType.STRING} and mirrored by
 * {@code ck_notification_type} in V18, so the database refuses a value that is
 * not one of these whatever wrote the row. Never ordinal - reordering these
 * constants would silently reinterpret every notification already raised.
 *
 * The set is deliberately small and closed. Each constant corresponds to one
 * place in the code that raises it, and adding one means adding that place -
 * not a general "something happened" type whose meaning lives in free text.
 */
public enum NotificationType {

    /** Work is due and this student has not handed it in yet. */
    DUE_SOON,

    /** A teacher recorded a mark against this student's work. */
    MARK_POSTED,

    /** This student was marked absent from a lesson. */
    ABSENCE_RECORDED,

    /** Somebody sent this person a message. */
    MESSAGE_RECEIVED
}
