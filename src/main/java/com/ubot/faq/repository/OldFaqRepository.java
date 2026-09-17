package com.ubot.faq.repository;

import com.ubot.faq.entity.OldFaq;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OldFaqRepository extends JpaRepository<OldFaq, Long> {
	List<OldFaq> findByFaqId(Long faqId);
	List<OldFaq> findByFaqCategoryId(Long faqCategoryId);
	void deleteByFaqId(Long faqId);
}
