package com.example.tracker.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.Check;

/**
 * A teaching group - "Grade 10A", "Form 3B".
 *
 * Named SchoolClass rather than Class for two independent reasons, either of
 * which would be enough: `class` is a Java keyword and cannot be a type name at
 * all, and `class` is a reserved word in SQL that would need quoting in every
 * query that touched it. Same reasoning that produced AppUser over User.
 *
 * A class is what makes "set this work for thirty people" a single action. The
 * teacher names the group, not the individuals, so the assignment does not have
 * to be re-issued when the register changes.
 */
@Entity
@Table(name = "school_class")
@Check(name = "ck_school_class_name_not_blank", constraints = "LTRIM(RTRIM(name)) <> ''")
public class SchoolClass {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Unique, because a class name is how people refer to the group. Two groups
     * both called "Grade 10A" would make every roll call ambiguous, and the
     * ambiguity would only surface once somebody was marked absent from the
     * wrong one.
     */
    @NotBlank(message = "Class name must not be blank")
    @Size(max = 50, message = "Class name must be at most 50 characters")
    @Column(nullable = false, length = 50, unique = true)
    private String name;

    @Version
    private Long version;

    protected SchoolClass() {
        // JPA needs a no-argument constructor to rebuild rows.
    }

    public SchoolClass(String name) {
        this.name = name;
    }

    public Long getId() {
        return id;
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
