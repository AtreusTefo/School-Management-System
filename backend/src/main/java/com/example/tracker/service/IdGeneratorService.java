package com.example.tracker.service;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Year;

/**
 * Issues the login id an admin-created account is identified by:
 * STU20260001 for a student, TCH20260001 for a teacher, ADM20260001 for an
 * admin.
 *
 * BACKED BY A DATABASE SEQUENCE, NOT "SELECT MAX(...) + 1"
 * The obvious alternative - read the highest number issued so far and add
 * one - is a genuine race under concurrent admin sessions: two admins
 * onboarding a student at the same moment could both read the same MAX
 * before either had written their row, and both try to issue the same id.
 * uq_app_user_external_id (a filtered unique index, see V8) would catch the
 * collision, but only as a 409 the second admin has to retry past. A
 * SEQUENCE is atomic by construction - SQL Server (and H2, in tests)
 * guarantees NEXT VALUE FOR never returns the same number twice, even under
 * concurrent callers, so there is nothing here to retry.
 *
 * THREE SEQUENCES, NOT ONE SHARED COUNTER
 * A shared counter would make "how many teachers have ever been created"
 * unanswerable from the id alone, since a batch of ten students between two
 * teachers would leave gaps that mean nothing. One sequence per role keeps
 * each numbering line meaningful on its own terms.
 */
@Service
public class IdGeneratorService {

    private final EntityManager entityManager;

    public IdGeneratorService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional
    public String nextStudentId() {
        return format("STU", nextValue("SELECT NEXT VALUE FOR seq_student_id"));
    }

    @Transactional
    public String nextTeacherId() {
        return format("TCH", nextValue("SELECT NEXT VALUE FOR seq_teacher_id"));
    }

    @Transactional
    public String nextAdminId() {
        return format("ADM", nextValue("SELECT NEXT VALUE FOR seq_admin_id"));
    }

    /**
     * The three query strings above are the only ones ever passed here -
     * literal constants written into this class's own methods, never built
     * from a caller-supplied value. There is nothing dynamic about them.
     */
    private long nextValue(String sql) {
        return ((Number) entityManager.createNativeQuery(sql).getSingleResult()).longValue();
    }

    private String format(String prefix, long sequenceValue) {
        return prefix + Year.now().getValue() + String.format("%04d", sequenceValue);
    }
}
