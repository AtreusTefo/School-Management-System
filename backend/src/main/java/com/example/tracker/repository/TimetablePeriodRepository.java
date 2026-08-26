package com.example.tracker.repository;

import com.example.tracker.model.TimetablePeriod;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** DATA ACCESS for the shape of the school day. */
public interface TimetablePeriodRepository extends JpaRepository<TimetablePeriod, Long> {

    Optional<TimetablePeriod> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    boolean existsBySequenceNo(Integer sequenceNo);

    Optional<TimetablePeriod> findBySequenceNo(Integer sequenceNo);

    /** The day in the order the school runs it - see TimetablePeriod.sequenceNo. */
    List<TimetablePeriod> findAllByOrderBySequenceNoAsc();
}
