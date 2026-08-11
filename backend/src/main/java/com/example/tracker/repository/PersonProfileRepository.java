package com.example.tracker.repository;

import com.example.tracker.model.AppUser;
import com.example.tracker.model.PersonProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
