package com.example.tracker.dto;

import com.example.tracker.model.AppUser;

/**
 * What the admin panel's teacher list is shown. Deliberately not the AppUser
 * entity - same reasoning as every other view in this package - so the
 * password hash cannot leak by accident if a field is ever added to the
 * entity later.
 */
public record TeacherView(Long id, String username, boolean mustChangePassword) {

    public static TeacherView of(AppUser teacher) {
        return new TeacherView(teacher.getId(), teacher.getUsername(),
                teacher.isMustChangePassword());
    }
}
