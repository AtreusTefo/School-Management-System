package com.example.tracker.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * MODEL LAYER
 * -----------
 * This class is an "Entity". It is a plain Java object that ALSO describes
 * a database table. Each field becomes a column; each object becomes a row.
 *
 * @Entity tells Spring/JPA: "Persist objects of this class in the database."
 */
@Entity
public class Assignment {

    /**
     * @Id marks the primary key (the unique identifier for each row).
     * @GeneratedValue means the database auto-assigns the number (1, 2, 3...),
     * so we never set the id ourselves.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // The name of the assignment, e.g. "Math Homework 1".
    private String title;

    // A simple text status. In this prototype we use plain Strings:
    // "IN_PROGRESS" or "SUBMITTED". (A real app might use an enum.)
    private String status;

    // JPA REQUIRES a no-argument constructor to build objects from DB rows.
    public Assignment() {
    }

    // A convenience constructor we use to create sample data at startup.
    public Assignment(String title, String status) {
        this.title = title;
        this.status = status;
    }

    // ----- Getters and Setters -----
    // These let other layers (and JPA) read/write the private fields.
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
