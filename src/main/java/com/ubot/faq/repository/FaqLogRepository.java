package com.ubot.faq.repository;

import com.ubot.faq.entity.FaqCategory;
import com.ubot.faq.entity.FaqLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FaqLogRepository extends JpaRepository<FaqLog, Long> {

}
