package com.example.tracker.repository;

import com.example.tracker.model.Assignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * REPOSITORY (DATA ACCESS) LAYER
 * ------------------------------
 * This is the ONLY layer that talks to the database.
 *
 * Notice there is no code inside — just an interface. This is the magic of
 * Spring Data JPA: by extending JpaRepository, Spring AUTOMATICALLY generates
 * a full implementation at runtime with methods like:
 *
 *   findAll()          -> SELECT * FROM assignment
 *   findById(id)       -> SELECT * FROM assignment WHERE id = ?
 *   save(assignment)   -> INSERT or UPDATE
 *   deleteById(id)     -> DELETE
 *
 * The two generic types <Assignment, Long> mean:
 *   - We manage "Assignment" objects
 *   - Its @Id (primary key) is of type "Long"
 */
@Repository
public interface AssignmentRepository extends JpaRepository<Assignment, Long> {
    // Empty on purpose. We inherit all standard CRUD methods for free.
    // (Custom queries would be declared here later, e.g. findByStatus(...).)
}
