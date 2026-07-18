package com.vasundhara.atf.db.repository;

import com.vasundhara.atf.db.entity.AppUserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUserEntity, Long> {

    Optional<AppUserEntity> findByUsernameAndActiveTrue(String username);

    boolean existsByUsername(String username);
}
