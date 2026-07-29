package com.example.tracker.repository;

import com.example.tracker.model.AppUser;
import com.example.tracker.model.Assignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * REPOSITORY (DATA ACCESS) LAYER
 * ------------------------------
 * This is the ONLY layer that talks to the database.
 *
 * Notice there is almost no code: by extending JpaRepository, Spring Data
 * generates a full implementation at runtime with findAll, findById, save,
 * count and deleteById.
 *
 * The two methods below are DERIVED QUERIES - Spring reads the method name and
 * writes the SQL. "findByOwnerOrderByIdAsc" becomes
 * "SELECT ... WHERE owner_id = ? ORDER BY id ASC".
 *
 * The scoping query belongs here rather than as a filter in the service, and
 * certainly not in the frontend: restricting rows in the QUERY means another
 * person's data is never loaded, never serialised, and never sent. Fetching
 * everything and hiding some of it in the UI is not access control.
 */
@Repository
public interface AssignmentRepository extends JpaRepository<Assignment, Long> {

    /** Everything, oldest first - what a teacher sees. */
    List<Assignment> findAllByOrderByIdAsc();

    /** Only this person's assignments - what a student sees. */
    List<Assignment> findByOwnerOrderByIdAsc(AppUser owner);

    /** Used by the seed guard to decide whether the table is already populated. */
    boolean existsByOwner(AppUser owner);
}
