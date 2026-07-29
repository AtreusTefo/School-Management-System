package com.example.tracker.security;

import com.example.tracker.model.AppUser;
import com.example.tracker.repository.AppUserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Bridges our AppUser entity to what Spring Security expects.
 *
 * Spring Security never sees our entity directly; it works with its own
 * UserDetails type. This class is the single translation point, which keeps the
 * framework out of the model and the model out of the framework.
 *
 * WHY THE ROLE GETS AN "ROLE_" PREFIX
 * Spring Security's hasRole("TEACHER") looks for an authority literally named
 * "ROLE_TEACHER". Our enum stores "TEACHER" because that is the domain's word
 * for it; the prefix is a framework convention and is added here, at the
 * boundary, rather than polluting the enum with it.
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;

    public AppUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        AppUser user = users.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "No account found for '" + username + "'"));

        return new User(
                user.getUsername(),
                user.getPasswordHash(),
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
    }
}
