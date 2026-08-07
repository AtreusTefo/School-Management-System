package com.example.tracker.service;

import com.example.tracker.dto.AuditLogView;
import com.example.tracker.exception.AccessDeniedException;
import com.example.tracker.model.AppUser;
import com.example.tracker.model.AuditAction;
import com.example.tracker.model.AuditLog;
import com.example.tracker.model.Role;
import com.example.tracker.repository.AppUserRepository;
import com.example.tracker.repository.AuditLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * SERVICE (BUSINESS LOGIC) LAYER for the audit log.
 *
 * Two jobs that could not be more different in who may call them:
 *
 *   record(...) is called by every OTHER service in this codebase, from
 *   inside their own write methods. It is not exposed through any
 *   controller, and it enforces no authority of its own - the caller has
 *   already established who "me" is and that they were allowed to make the
 *   change; this only writes down what happened.
 *
 *   search(...) is the admin-facing read, and IS gated - only ADMIN may see
 *   the log.
 *
 * ONE TRANSACTION, NOT A SEPARATE ONE
 * record() is always invoked from within the caller's own @Transactional
 * method, never scheduled or fired-and-forgotten. That is deliberate: an
 * audit entry and the change it describes either both commit or both roll
 * back. A log write that could fail silently, or succeed while the change it
 * describes rolled back, would not be trustworthy - and "immutable audit
 * log" implies "accurate audit log" as a precondition.
 *
 * WHY THIS READS THE SECURITY CONTEXT DIRECTLY INSTEAD OF CALLING
 * AppUserService.currentActiveUser()
 * Every OTHER service's "am I allowed to do this" guard is a private method
 * that delegates to AppUserService - that is the norm, not this class's.
 * The exception is forced: AppUserService.createStudent is itself a
 * Create operation this EPIC asks to be logged, so AppUserService needs to
 * depend on AuditLogService. If AuditLogService also depended on
 * AppUserService, that would be a cycle Spring's constructor injection
 * cannot resolve. Depending on AppUserRepository directly instead - the same
 * repository AppUserService itself wraps - breaks the cycle while changing
 * nothing about what the guard actually checks.
 */
@Service
@Transactional(readOnly = true)
public class AuditLogService {

    private final AuditLogRepository log;
    private final AppUserRepository users;

    public AuditLogService(AuditLogRepository log, AppUserRepository users) {
        this.log = log;
        this.users = users;
    }

    /**
     * Write one entry.
     *
     * Takes the actor as a parameter rather than deriving it from the
     * session, because every call site already holds it - the same `me` that
     * was just checked for authority to make the change is who made it. Re-
     * deriving it here would be a second lookup answering a question the
     * caller had already answered, and could theoretically disagree with it.
     */
    @Transactional
    public void record(String entityName, Long entityId, AuditAction action,
                       AppUser performedBy, String summary) {
        log.save(new AuditLog(entityName, entityId, action, performedBy, summary, Instant.now()));
    }

    /**
     * The paginated, filterable listing behind the admin panel's audit log
     * page. Every filter is optional; omitting one simply widens the search.
     */
    public Page<AuditLogView> search(String entityName, AuditAction action,
                                     Instant from, Instant to, Pageable pageable) {
        requireAdmin("view the audit log");
        return log.search(entityName, action, from, to, pageable).map(AuditLogView::of);
    }

    /**
     * The caller's own copy of "who am I, and are they an admin" - see the
     * class comment for why this cannot simply call
     * AppUserService.currentActiveUser() the way every other service's
     * equivalent guard does.
     */
    private AppUser requireAdmin(String action) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("No authenticated user on this request.");
        }
        AppUser me = Require.orThrow(users.findByUsername(authentication.getName()),
                () -> new IllegalStateException(
                        "Authenticated user '" + authentication.getName() + "' no longer exists."));
        if (me.isMustChangePassword()) {
            throw new AccessDeniedException(
                    "Your password must be changed before you can use the system.");
        }
        if (me.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("Only an admin can " + action + ".");
        }
        return me;
    }
}
