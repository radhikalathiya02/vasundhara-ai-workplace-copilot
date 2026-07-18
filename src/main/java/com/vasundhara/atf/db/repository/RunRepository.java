package com.vasundhara.atf.db.repository;

import com.vasundhara.atf.db.entity.RunEntity;
import com.vasundhara.atf.model.RunState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface RunRepository extends JpaRepository<RunEntity, String> {

    List<RunEntity> findAllByOrderByCreatedAtDesc();

    List<RunEntity> findByStateInOrderByCreatedAtDesc(List<RunState> states);

    @Query("SELECT r FROM RunEntity r WHERE r.packageName = :pkg AND r.state = 'COMPLETED' AND r.runId <> :excludeId ORDER BY r.createdAt DESC")
    List<RunEntity> findBaselineRuns(String pkg, String excludeId);
}
