package com.example.tracker.repository;

import com.example.tracker.model.Term;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * DATA ACCESS for terms. No business rules live here - see TimetableService.
 */
public interface TermRepository extends JpaRepository<Term, Long> {

    Optional<Term> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    /** The school year in the order it happens, which is how every page shows it. */
    List<Term> findAllByOrderByStartDateAsc();

    /**
     * Every term that shares at least one day with the range given.
     *
     * Two ranges overlap when each starts on or before the other ends - the
     * whole test, with no special case for containment or for identical dates.
     * Used by TimetableService to refuse an overlapping term, which is the one
     * rule in this area the database cannot state for itself (SQL Server has no
     * exclusion constraint); see Term's class comment.
     */
    List<Term> findByStartDateLessThanEqualAndEndDateGreaterThanEqual(
            LocalDate onOrBeforeEnd, LocalDate onOrAfterStart);
}
