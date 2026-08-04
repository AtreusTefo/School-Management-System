package com.example.tracker.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.Checks;

/**
 * A subject that can be taught - Mathematics, History, and so on.
 *
 * A reference table: small, slow-changing, and pointed at by many rows. Its
 * whole job is to make "Mathematics" ONE thing rather than a string typed
 * repeatedly, because free text drifts. "Maths", "Math" and "Mathematics " with
 * a trailing space are three different subjects to a database and one subject to
 * a person, and no amount of later cleanup fully recovers from that.
 *
 * WHY @Check AS WELL AS THE MIGRATION
 * -----------------------------------
 * The CHECK constraints below are declared here AND in the Flyway migration, and
 * the duplication is deliberate. Flyway builds the SQL Server schema; the tests
 * run on H2 built from these entities. A constraint written only in the
 * migration is therefore untestable, and one written only here never reaches the
 * real database. Declaring both is what lets the suite prove the rule that
 * production actually enforces.
 */
@Entity
@Checks({
        @Check(name = "ck_subject_code_not_blank", constraints = "LTRIM(RTRIM(code)) <> ''"),
        @Check(name = "ck_subject_name_not_blank", constraints = "LTRIM(RTRIM(name)) <> ''")
})
public class Subject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The short form people type, e.g. "MATH". Unique. */
    @NotBlank(message = "Subject code must not be blank")
    @Size(max = 20, message = "Subject code must be at most 20 characters")
    @Column(nullable = false, length = 20, unique = true)
    private String code;

    /** The full name people read, e.g. "Mathematics". Also unique. */
    @NotBlank(message = "Subject name must not be blank")
    @Size(max = 100, message = "Subject name must be at most 100 characters")
    @Column(nullable = false, length = 100, unique = true)
    private String name;

    @Version
    private Long version;

    protected Subject() {
        // JPA needs a no-argument constructor to rebuild rows.
    }

    public Subject(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getVersion() {
        return version;
    }
}
