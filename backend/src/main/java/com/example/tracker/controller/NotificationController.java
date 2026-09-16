package com.example.tracker.controller;

import com.example.tracker.dto.NotificationView;
import com.example.tracker.service.NotificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * WEB LAYER for notifications.
 *
 * HTTP only. Every endpoint here is implicitly scoped to the caller, and that
 * scoping is NotificationService's - there is no path parameter naming whose
 * notifications to read, because there is no such thing as reading somebody
 * else's. The absence of that parameter is the design, not an omission.
 *
 * PUT for marking read, for the same reason AttendanceController uses it:
 * marking something read twice must leave it exactly as it was after the
 * first time, and the entity's markRead() makes that true rather than moving
 * the timestamp.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    /** The caller's own notifications, newest first. */
    @GetMapping
    public List<NotificationView> mine(
            @RequestParam(name = "unreadOnly", defaultValue = "false") boolean unreadOnly) {
        return notifications.listMine(unreadOnly);
    }

    /**
     * The number on the bell.
     *
     * A separate endpoint rather than a count taken from the list above,
     * because the app bar asks for it on every navigation and does not want
     * the rows - and because the unread count is served by a filtered index
     * that only covers unread rows.
     */
    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount() {
        return Map.of("unread", notifications.unreadCount());
    }

    @PutMapping("/{id}/read")
    public NotificationView markRead(@PathVariable Long id) {
        return notifications.markRead(id);
    }

    /** Returns how many were still unread, so the client can update without refetching. */
    @PutMapping("/read-all")
    public Map<String, Integer> markAllRead() {
        return Map.of("marked", notifications.markAllRead());
    }
}
