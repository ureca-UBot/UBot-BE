package com.ubot.faq.repository;

import com.ubot.faq.entity.FaqCategory;
import com.ubot.faq.entity.FaqLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FaqLogRepository extends JpaRepository<FaqLog, Long> {
	List<FaqLog> findByQuestionLogId(Long questionLogId);
	List<FaqLog> findByFaqId(Long faqId);
}
