package com.example.tracker.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.Checks;

import java.time.LocalDate;

/**
 * The biographical detail an admin's student/teacher forms collect - full
 * name, national id, date of birth, contact email, when they enrolled.
 *
 * DELIBERATELY NOT PART OF AppUser
 * AppUser is an identity and a credential: a username, a password hash, a
 * role. This is a different kind of fact about the same person, asked for by
 * a different part of the system (the admin panel's onboarding forms) for a
 * different reason (record-keeping, not authentication). Every query that
 * only needs "who is this and what can they do" - which is most of them -
 * has no reason to carry a date of birth past it.
 *
 * A SHARED PRIMARY KEY WITH app_user, NOT A SURROGATE ONE
 * This row has no identity except "the profile belonging to this account".
 * @MapsId makes app_user_id both the foreign key AND the primary key, so
 * there is no second id that could, through some future bug, end up
 * pointing at a different account than the one this profile is shown on.
 *
 * WHAT IS DELIBERATELY NOT HERE: A GRADE OR A LIST OF SUBJECTS
 * Both are asked for on the admin forms, and both are already fully
 * expressed elsewhere - a student's grade is which SchoolClass they are
 * enrolled in (Enrolment), and a student's or teacher's subjects are the
 * Course rows attached to that class. Storing them again here would be a
 * second copy of a fact the timetable already owns, and the two would
 * eventually disagree the first time a student changed class without this
 * table being told. AdminService.createStudent/createTeacher take an
 * initial class or course assignment as part of the same request, but they
 * write it through EnrolmentRepository/CourseRepository, never here.
 */
@Entity
@Table(name = "person_profile")
@Checks({
        @Check(name = "ck_person_profile_first_name_not_blank",
                constraints = "LTRIM(RTRIM(first_name)) <> ''"),
        @Check(name = "ck_person_profile_last_name_not_blank",
                constraints = "LTRIM(RTRIM(last_name)) <> ''")
})
public class PersonProfile {

    @Id
    @Column(name = "app_user_id")
    private Long id;

    @NotNull
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(
            name = "app_user_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_person_profile_app_user"))
    private AppUser appUser;

    @NotBlank
    @Size(max = 50)
    @Column(name = "first_name", nullable = false, length = 50)
    private String firstName;

    /** "If it exists" - see the admin form's own wording. Not everyone has one. */
    @Size(max = 50)
    @Column(name = "middle_name", length = 50)
    private String middleName;

    @NotBlank
    @Size(max = 50)
    @Column(name = "last_name", nullable = false, length = 50)
    private String lastName;

    /** OMANG / national id. A real-world identifier this system does not issue. */
    @NotBlank
    @Size(max = 20)
    @Column(name = "national_id", nullable = false, length = 20, unique = true)
    private String nationalId;

    @NotNull
    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    /** Contact information, never a login credential - see the class comment. */
    @Size(max = 100)
    @Column(name = "contact_email", length = 100)
    private String contactEmail;

    @NotNull
    @Column(name = "date_enrolled", nullable = false)
    private LocalDate dateEnrolled;

    /**
     * A Botswana mobile number in E.164 form: +267 followed by eight digits.
     *
     * NULLABLE for the same reason contactEmail is - see V9's own header. The
     * profiles created before this column existed have no honest value to
     * backfill it with; every profile the admin forms save from here on is
     * required to carry one at the DTO layer, and ck_person_profile_phone_format
     * enforces the shape at the database whichever layer wrote it.
     */
    @Size(max = 20)
    @Column(name = "phone", length = 20)
    private String phone;

    @jakarta.persistence.Version
    @JsonIgnore
    private Long version;

    protected PersonProfile() {
        // JPA needs a no-argument constructor to rebuild rows.
    }

    public PersonProfile(AppUser appUser, String firstName, String middleName, String lastName,
                         String nationalId, LocalDate dateOfBirth, String contactEmail,
                         String phone, LocalDate dateEnrolled) {
        this.appUser = appUser;
        this.firstName = firstName;
        this.middleName = middleName;
        this.lastName = lastName;
        this.nationalId = nationalId;
        this.dateOfBirth = dateOfBirth;
        this.contactEmail = contactEmail;
        this.phone = phone;
        this.dateEnrolled = dateEnrolled;
    }

    public Long getId() {
        return id;
    }

    public AppUser getAppUser() {
        return appUser;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getMiddleName() {
        return middleName;
    }

    public void setMiddleName(String middleName) {
        this.middleName = middleName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getNationalId() {
        return nationalId;
    }

    /**
     * Correctable, deliberately.
     *
     * A national id is identity-defining, which argues for making it immutable
     * - but it is also typed in by hand at onboarding, and a typo in it is
     * precisely the thing an admin edit exists to fix. Refusing to correct one
     * would force the account to be deleted and recreated, losing its
     * generated login id and its audit history to fix a single character.
     *
     * The value stays guarded either way: uq_person_profile_national_id refuses
     * a change that would collide with another person, whatever this setter is
     * asked to do.
     */
    public void setNationalId(String nationalId) {
        this.nationalId = nationalId;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public void setContactEmail(String contactEmail) {
        this.contactEmail = contactEmail;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public LocalDate getDateEnrolled() {
        return dateEnrolled;
    }

    public void setDateEnrolled(LocalDate dateEnrolled) {
        this.dateEnrolled = dateEnrolled;
    }

    public Long getVersion() {
        return version;
    }

    /** "First Middle Last", or "First Last" when there is no middle name. */
    @Transient
    public String getFullName() {
        return (middleName == null || middleName.isBlank())
                ? firstName + " " + lastName
                : firstName + " " + middleName + " " + lastName;
    }
}
