package com.example.tracker.repository;

import com.example.tracker.model.Subject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Data access for subjects. Reference data: read constantly, written rarely. */
@Repository
public interface SubjectRepository extends JpaRepository<Subject, Long> {

    /** Alphabetical, because a dropdown in insertion order is a dropdown nobody can use. */
    List<Subject> findAllByOrderByNameAsc();

    Optional<Subject> findByCodeIgnoreCase(String code);

    Optional<Subject> findByNameIgnoreCase(String name);
}
