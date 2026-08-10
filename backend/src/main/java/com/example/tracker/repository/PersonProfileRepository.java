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

    boolean existsByNationalId(String nationalId);
}
