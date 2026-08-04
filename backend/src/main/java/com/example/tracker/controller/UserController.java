package com.example.tracker.controller;

import com.example.tracker.model.AppUser;
import com.example.tracker.service.AppUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * CREATING ACCOUNTS (presentation layer) - US-23.
 *
 * HTTP only, like every controller here. It does not decide who may create an
 * account, what roles exist, or whether a username is taken; it takes a request,
 * hands it to AppUserService, and returns what came back.
 *
 * SEPARATE FROM AuthController ON PURPOSE.
 * AuthController is about the CALLER - proving who you are, and managing your own
 * credentials. This is about somebody else: a teacher acting on a third party's
 * account. They are different responsibilities with different authority rules,
 * and merging them would put "anyone signed in may do this" and "only a teacher
 * may do this" in the same class.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final AppUserService users;

    public UserController(AppUserService users) {
        this.users = users;
    }

    /**
     * POST /api/users - create a student account.
     *
     * 201 Created rather than 200, because this makes a new resource. The
     * existing endpoints return 200 for updates and 204 for a delete; a create
     * that answered 200 would be the odd one out for no reason.
     *
     * The response deliberately carries no password. The caller supplied the
     * temporary one, so echoing it back would add nothing and would put a live
     * credential into logs and browser history.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AuthController.UserView create(@Valid @RequestBody CreateUserRequest request) {
        AppUser created = users.createStudent(
                request.getUsername(), request.getTemporaryPassword());
        return AuthController.UserView.of(created);
    }

    /**
     * What a teacher may send when creating an account.
     *
     * NOTE WHAT IS ABSENT: there is no role field. A student is the only kind of
     * account this endpoint makes, so no request - however it is crafted - can
     * ask for a TEACHER. Leaving the field out entirely is a stronger guarantee
     * than accepting one and validating it, because there is nothing to get the
     * validation wrong about.
     */
    static class CreateUserRequest {
        @NotBlank(message = "Username must not be blank")
        @Size(max = 50, message = "Username must be at most 50 characters")
        private String username;

        @NotBlank(message = "Temporary password must not be blank")
        @Size(min = 8, message = "Temporary password must be at least 8 characters")
        private String temporaryPassword;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getTemporaryPassword() {
            return temporaryPassword;
        }

        public void setTemporaryPassword(String temporaryPassword) {
            this.temporaryPassword = temporaryPassword;
        }
    }
}
