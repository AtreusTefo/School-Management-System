package com.example.tracker.repository;

import com.example.tracker.model.SchoolClass;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Data access for teaching groups. */
@Repository
public interface SchoolClassRepository extends JpaRepository<SchoolClass, Long> {

    List<SchoolClass> findAllByOrderByNameAsc();

    Optional<SchoolClass> findByNameIgnoreCase(String name);
}
