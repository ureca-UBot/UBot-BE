package com.ubot.faq.repository;

import com.ubot.faq.entity.OldFaq;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OldFaqRepository extends JpaRepository<OldFaq, Long> {

}
