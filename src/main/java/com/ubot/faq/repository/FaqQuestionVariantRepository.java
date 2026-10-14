package com.ubot.faq.repository;

import com.ubot.faq.entity.FaqQuestionVariant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FaqQuestionVariantRepository extends JpaRepository<FaqQuestionVariant, Long> {
    List<FaqQuestionVariant> findAllByOriginFaq_IdOrderByIdAsc(Long originFaqId);

    Optional<FaqQuestionVariant> findByOriginFaq_IdAndQuestion(Long originFaqId, String question);
}
