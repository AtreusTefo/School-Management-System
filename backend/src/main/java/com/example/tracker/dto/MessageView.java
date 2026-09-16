package com.example.tracker.dto;

import com.example.tracker.model.Message;

import java.time.Instant;

/**
 * One message, as the API publishes it.
 *
 * Both ends are named, with their roles, because a message is drawn in three
 * different places - an inbox, a sent list, and a conversation - and each
 * needs a different one of them to be "the other person". Publishing both and
 * letting the caller pick is simpler than three near-identical records, and
 * the roles come off the pinned columns rather than a join, so they cannot
 * disagree with the accounts.
 */
public record MessageView(
        Long id,
        Long senderId,
        String senderUsername,
        String senderRole,
        Long recipientId,
        String recipientUsername,
        String recipientRole,
        String body,
        Instant sentAt,
        boolean read) {

    public static MessageView of(Message m) {
        return new MessageView(
                m.getId(),
                m.getSender().getId(),
                m.getSender().getUsername(),
                m.getSender().getRole().name(),
                m.getRecipient().getId(),
                m.getRecipient().getUsername(),
                m.getRecipient().getRole().name(),
                m.getBody(),
                m.getSentAt(),
                m.isRead());
    }
}
