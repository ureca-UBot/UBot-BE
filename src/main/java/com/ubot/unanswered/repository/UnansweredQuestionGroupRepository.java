package com.ubot.unanswered.repository;

import com.ubot.unanswered.entity.UnansweredQuestionGroup;
import com.ubot.unanswered.enums.UnansweredGroupStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UnansweredQuestionGroupRepository extends JpaRepository<UnansweredQuestionGroup, Long> {
	Page<UnansweredQuestionGroup> findAllByQuestionCountGreaterThanEqual(Integer minCount, Pageable pageable);

	Page<UnansweredQuestionGroup> findAllByStatusAndQuestionCountGreaterThanEqual(
			UnansweredGroupStatus status,
			Integer minCount,
			Pageable pageable
	);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select g from UnansweredQuestionGroup g where g.id = :id")
	Optional<UnansweredQuestionGroup> findByIdForUpdate(@Param("id") Long id);
}
