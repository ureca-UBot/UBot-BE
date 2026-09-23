package com.ubot.faq.repository;

import com.ubot.faq.entity.OldFaq;
import com.ubot.faq.entity.id.OldFaqId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OldFaqRepository extends JpaRepository<OldFaq, OldFaqId> {
	Page<OldFaq> findByFaqId(Long faqId, Pageable pageable);
}