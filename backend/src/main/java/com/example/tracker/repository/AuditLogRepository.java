package com.example.tracker.repository;

import com.example.tracker.model.AuditAction;
import com.example.tracker.model.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * Data access for the audit log.
 *
 * One query, because the admin page's filters (entity, action, a date range)
 * are all optional and all combine - four separate derived-method queries
 * would not express "any subset of these at once" without a query per
 * combination. Written as JPQL with `:param IS NULL OR ...` guards for each
 * filter, the standard Spring Data shape for "search with optional criteria".
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    @Query("""
            SELECT a FROM AuditLog a
            WHERE (:entityName IS NULL OR a.entityName = :entityName)
              AND (:action IS NULL OR a.action = :action)
              AND (:from IS NULL OR a.performedAt >= :from)
              AND (:to IS NULL OR a.performedAt <= :to)
            ORDER BY a.performedAt DESC
            """)
    Page<AuditLog> search(@Param("entityName") String entityName,
                          @Param("action") AuditAction action,
                          @Param("from") Instant from,
                          @Param("to") Instant to,
                          Pageable pageable);
}
