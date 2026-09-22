package com.ubot.faq.repository;

import com.ubot.faq.entity.FaqLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FaqLogRepository extends JpaRepository<FaqLog, Long> {
	Page<FaqLog> findByQuestionLogId(Long questionLogId, Pageable pageable);
	Page<FaqLog> findByFaqId(Long faqId, Pageable pageable);
}