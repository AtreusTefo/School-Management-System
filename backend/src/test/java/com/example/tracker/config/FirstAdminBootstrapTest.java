package com.example.tracker.config;

import com.example.tracker.model.AppUser;
import com.example.tracker.model.Role;
import com.example.tracker.repository.AppUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * FirstAdminBootstrap, with the repository mocked.
 *
 * The property that matters most is the one a reader is least likely to check
 * by hand: once ANY administrator exists, the variable does nothing at all, so
 * leaving it in the hosting configuration is not a standing way back in.
 *
 * A real BCryptPasswordEncoder at the lowest cost factor, rather than a mock,
 * so the test proves the stored value actually verifies against the password.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressWarnings("null") // Mockito's any()/captor signatures are unannotated third-party code
class FirstAdminBootstrapTest {

    private static final String GOOD_PASSWORD = "a-long-enough-temporary-pass";

    @Mock
    private AppUserRepository users;

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);

    private FirstAdminBootstrap bootstrap(String password) {
        return new FirstAdminBootstrap(users, encoder, password);
    }

    @Test
    @DisplayName("does nothing when the variable is not set")
    void unsetIsANoOp() {
        bootstrap("").run(null);
        bootstrap("   ").run(null);

        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("does nothing once any administrator exists, even with the variable still set")
    void existingAdminMakesItInert() {
        AppUser existing = new AppUser("ADM20260001", "hash", Role.ADMIN);
        when(users.findByRoleOrderByUsernameAsc(Role.ADMIN)).thenReturn(List.of(existing));

        bootstrap(GOOD_PASSWORD).run(null);

        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("refuses to start with a password shorter than the minimum")
    void shortPasswordRefusesToStart() {
        when(users.findByRoleOrderByUsernameAsc(Role.ADMIN)).thenReturn(List.of());

        assertThatThrownBy(() -> bootstrap("short").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(String.valueOf(FirstAdminBootstrap.MIN_LENGTH));
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("creates one admin who must change the password at first sign-in")
    void createsTheFirstAdmin() {
        when(users.findByRoleOrderByUsernameAsc(Role.ADMIN)).thenReturn(List.of());
        when(users.findByUsername(FirstAdminBootstrap.USERNAME)).thenReturn(Optional.empty());

        bootstrap(GOOD_PASSWORD).run(null);

        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(users, times(1)).save(saved.capture());
        AppUser admin = saved.getValue();
        assertThat(admin.getUsername()).isEqualTo(FirstAdminBootstrap.USERNAME);
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.isMustChangePassword()).isTrue();
        assertThat(encoder.matches(GOOD_PASSWORD, admin.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("refuses rather than overwriting a non-admin account that already has the name")
    void nameTakenByAnotherRoleRefuses() {
        when(users.findByRoleOrderByUsernameAsc(Role.ADMIN)).thenReturn(List.of());
        when(users.findByUsername(FirstAdminBootstrap.USERNAME))
                .thenReturn(Optional.of(new AppUser("admin", "hash", Role.TEACHER)));

        assertThatThrownBy(() -> bootstrap(GOOD_PASSWORD).run(null))
                .isInstanceOf(IllegalStateException.class);
        verify(users, never()).save(any());
    }
}
