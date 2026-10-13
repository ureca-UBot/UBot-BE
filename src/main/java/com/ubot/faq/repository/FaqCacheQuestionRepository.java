package com.ubot.faq.repository;

import com.ubot.faq.entity.FaqCacheQuestion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FaqCacheQuestionRepository extends JpaRepository<FaqCacheQuestion, Long> {
    Optional<FaqCacheQuestion> findByFaq_IdAndNormalizedQuestionAndProfileId(
            Long faqId, String normalizedQuestion, Long profileId);

    List<FaqCacheQuestion> findAllByFaq_IdOrderByIdAsc(Long faqId);

    Optional<FaqCacheQuestion> findByIdAndFaq_Id(Long id, Long faqId);
}
