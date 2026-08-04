package com.example.tracker.dto;

import com.example.tracker.model.Subject;

/**
 * A subject as the API publishes it.
 *
 * WHY THE API RETURNS RECORDS RATHER THAN ENTITIES
 * ------------------------------------------------
 * The old endpoints serialised entities directly, which worked while the model
 * was one flat table and stopped working the moment it was not. Two concrete
 * problems, both of which this package exists to prevent:
 *
 *   LAZY LOADING. With open-in-view disabled the transaction closes before
 *   Jackson runs, so serialising an entity with a lazy association fails at
 *   render time - after the response has begun. Building the record inside the
 *   service reads what it needs while the transaction is still open.
 *
 *   ACCIDENTAL DISCLOSURE. An entity graph is walked as far as it reaches. One
 *   association added later, without @JsonIgnore, quietly widens what the API
 *   publishes - password hashes and document bytes included. A record exposes
 *   exactly the fields it declares and nothing follows from them.
 *
 * These are compile-time DTOs, not a mapping framework. The conversion is a
 * static factory on each record: obvious, greppable, and impossible to get
 * subtly wrong through configuration.
 */
public record SubjectView(Long id, String code, String name) {

    public static SubjectView of(Subject subject) {
        return new SubjectView(subject.getId(), subject.getCode(), subject.getName());
    }
}
