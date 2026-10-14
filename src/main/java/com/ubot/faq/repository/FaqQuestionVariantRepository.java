package com.ubot.faq.repository;

import com.ubot.faq.entity.FaqQuestionVariant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FaqQuestionVariantRepository extends JpaRepository<FaqQuestionVariant, Long> {
}
