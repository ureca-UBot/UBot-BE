package com.ubot.unanswered.repository;

import com.ubot.unanswered.entity.UnansweredQuestionGroup;
import com.ubot.unanswered.enums.UnansweredGroupStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UnansweredQuestionGroupRepository extends JpaRepository<UnansweredQuestionGroup, Long> {
	Page<UnansweredQuestionGroup> findAllByStatus(UnansweredGroupStatus status, Pageable pageable);
}
