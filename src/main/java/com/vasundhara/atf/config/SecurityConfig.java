package com.vasundhara.atf.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Secures the dashboard and API behind a form-based sign-in. A single configurable
 * account (atf.auth-username / atf.auth-password) is provisioned in memory; the session
 * cookie then authenticates all same-origin dashboard and API calls. CSRF is disabled
 * because the front end is a same-origin fetch-based SPA with a static login page.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // TEMPORARY: login page disabled — all requests permitted without authentication.
        // To restore login, revert this method to require auth (see git history) and remove
        // this comment block.
        http
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(AtfProperties props, PasswordEncoder encoder) {
        // Resolve the account from the live settings on each lookup, so credentials changed in
        // the Settings module take effect immediately (no restart needed).
        return username -> {
            if (username != null && username.equalsIgnoreCase(props.getAuthUsername())) {
                return User.withUsername(props.getAuthUsername())
                        .password(encoder.encode(props.getAuthPassword()))
                        .roles("QA")
                        .build();
            }
            throw new UsernameNotFoundException("Unknown user: " + username);
        };
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
