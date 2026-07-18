package com.vasundhara.atf.db.repository;

import com.vasundhara.atf.db.entity.ModuleSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ModuleSessionRepository extends JpaRepository<ModuleSessionEntity, String> {

    List<ModuleSessionEntity> findByModuleOrderByCreatedAtDesc(String module);
}
