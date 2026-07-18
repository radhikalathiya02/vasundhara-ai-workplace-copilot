package com.vasundhara.atf.db.service;

import com.vasundhara.atf.config.AtfProperties;
import com.vasundhara.atf.db.entity.AppUserEntity;
import com.vasundhara.atf.db.repository.AppUserRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the admin user row from {@link AtfProperties} on first startup.
 * On subsequent startups it updates the password hash if the configured password changed.
 */
@Component
public class DatabaseInitializer {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);

    private final AppUserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final AtfProperties props;

    public DatabaseInitializer(AppUserRepository userRepo,
                                PasswordEncoder passwordEncoder,
                                AtfProperties props) {
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
        this.props = props;
    }

    @PostConstruct
    @Transactional
    public void init() {
        try {
            String username = props.getAuthUsername();
            String rawPassword = props.getAuthPassword();

            userRepo.findByUsernameAndActiveTrue(username).ifPresentOrElse(
                    user -> {
                        // Update hash only when the stored hash doesn't match (password changed in config)
                        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
                            user.setPasswordHash(passwordEncoder.encode(rawPassword));
                            userRepo.save(user);
                            log.info("Updated password hash for admin user '{}'.", username);
                        }
                    },
                    () -> {
                        AppUserEntity admin = new AppUserEntity(
                                username, passwordEncoder.encode(rawPassword), "ADMIN");
                        userRepo.save(admin);
                        log.info("Created admin user '{}' in database.", username);
                    });
        } catch (Exception e) {
            log.warn("Could not seed admin user (non-fatal): {}", e.getMessage());
        }
    }
}
