package com.example.tracker.dto;

import java.time.Instant;

/**
 * One person this account has corresponded with, and the state of it.
 *
 * DERIVED ON READ, never stored. There is no conversation table - see
 * Message's class comment for why - so this is built by MessagingService from
 * the messages themselves each time the list is asked for. A stored
 * conversation row would need its lastMessageAt and unread count kept in step
 * with every send and every read, and would be wrong the first time one of
 * those updates was missed.
 */
public record ConversationView(
        Long personId,
        String username,
        String role,
        String fullName,
        String lastMessage,
        Instant lastMessageAt,
        long unread) {
}
