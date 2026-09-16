package com.example.tracker.controller;

import com.example.tracker.dto.ConversationView;
import com.example.tracker.dto.CorrespondentView;
import com.example.tracker.dto.MessageView;
import com.example.tracker.service.MessagingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * WEB LAYER for messages.
 *
 * HTTP only. Who may write to whom is MessagingService's rule and is checked
 * there, so it holds for every caller rather than for whoever arrives through
 * this class - which matters more than usual here, because the compose form
 * only OFFERS permitted recipients and a rule enforced only at the edge would
 * be bypassed by anyone posting an id of their own choosing.
 *
 * POST for sending, not PUT: sending the same message twice is two messages,
 * because saying something twice is saying it twice. That is the opposite of
 * the register and of marking something read, and the method says so.
 */
@RestController
@RequestMapping("/api/messages")
public class MessageController {

    private final MessagingService messaging;

    public MessageController(MessagingService messaging) {
        this.messaging = messaging;
    }

    /**
     * The request body for sending.
     *
     * Validated at the edge AND in the service, deliberately - see CLAUDE.md's
     * Validation section. The bound here mirrors message.body's NVARCHAR(2000),
     * so an over-long message is refused with a field error rather than as a
     * truncation or a constraint violation.
     */
    public record SendRequest(
            @NotNull(message = "A message needs somebody to send it to.")
            Long recipientId,

            @NotBlank(message = "A message needs something in it.")
            @Size(max = 2000, message = "A message cannot be longer than 2000 characters.")
            String body) { }

    @PostMapping
    public MessageView send(@Valid @RequestBody SendRequest request) {
        return messaging.send(request.recipientId(), request.body());
    }

    /** Everybody the caller has corresponded with, most recent first. */
    @GetMapping("/conversations")
    public List<ConversationView> conversations() {
        return messaging.conversations();
    }

    /** One conversation, oldest first. */
    @GetMapping("/conversations/{personId}")
    public List<MessageView> conversation(@PathVariable Long personId) {
        return messaging.conversationWith(personId);
    }

    /** Everybody the caller is allowed to write to - the compose form's list. */
    @GetMapping("/correspondents")
    public List<CorrespondentView> correspondents() {
        return messaging.listCorrespondents();
    }

    @GetMapping("/inbox")
    public List<MessageView> inbox() {
        return messaging.inbox();
    }

    @GetMapping("/sent")
    public List<MessageView> sent() {
        return messaging.sent();
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount() {
        return Map.of("unread", messaging.unreadCount());
    }

    @PutMapping("/{id}/read")
    public MessageView markRead(@PathVariable Long id) {
        return messaging.markRead(id);
    }
}
