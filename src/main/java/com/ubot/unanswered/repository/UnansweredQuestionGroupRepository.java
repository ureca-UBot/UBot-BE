package com.ubot.unanswered.repository;

import com.ubot.unanswered.entity.UnansweredQuestionGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UnansweredQuestionGroupRepository extends JpaRepository<UnansweredQuestionGroup, Long> {
}
