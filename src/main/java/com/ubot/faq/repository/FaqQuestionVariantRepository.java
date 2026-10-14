package com.ubot.faq.repository;

import com.ubot.faq.entity.FaqQuestionVariant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FaqQuestionVariantRepository extends JpaRepository<FaqQuestionVariant, Long> {
    @Query("""
            select v from FaqQuestionVariant v
            where v.originFaq.id = :originFaqId
              and v.originFaq.deletedAt is null and v.deletedAt is null
            order by v.id
            """)
    List<FaqQuestionVariant> findAllActiveByOriginFaqId(@Param("originFaqId") Long originFaqId);

    @Query("""
            select v from FaqQuestionVariant v
            where v.originFaq.id = :originFaqId and v.question = :question
              and v.originFaq.deletedAt is null and v.deletedAt is null
            """)
    Optional<FaqQuestionVariant> findActiveByOriginFaqIdAndQuestion(
            @Param("originFaqId") Long originFaqId, @Param("question") String question);
}
