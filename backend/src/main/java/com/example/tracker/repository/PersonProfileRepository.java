package com.example.tracker.repository;

import com.example.tracker.model.AppUser;
import com.example.tracker.model.PersonProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Data access for the biographical detail behind a student or teacher account. */
@Repository
public interface PersonProfileRepository extends JpaRepository<PersonProfile, Long> {

    Optional<PersonProfile> findByAppUser(AppUser appUser);

    /**
     * Used when EDITING a profile, where existsByNationalId is the wrong
     * question. An edit that leaves the national id untouched would collide
     * with itself under an exists check and refuse every save; the caller needs
     * the owning row back so it can ask "is this somebody else's?" instead.
     */
    Optional<PersonProfile> findByNationalId(String nationalId);

    boolean existsByNationalId(String nationalId);

    /**
     * Every profile carrying this contact email - a LIST, not an Optional.
     *
     * contact_email is deliberately NOT unique (see V11: siblings sharing a
     * parent's inbox is a real and legitimate case), so a lookup by it can
     * genuinely match more than one person. Returning Optional would force an
     * arbitrary choice at the point of the query and hide that fact from the
     * caller; returning the list makes the ambiguity the caller's to handle,
     * which for a password reset it must be.
     *
     * Replaces a findAll()-then-filter in PasswordResetService, which loaded
     * every profile in the school to find one address.
     */
    List<PersonProfile> findByContactEmailIgnoreCase(String contactEmail);
}
