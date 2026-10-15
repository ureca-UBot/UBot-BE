package com.ubot.faq.repository;

import com.ubot.faq.entity.FaqQuestionVariant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FaqQuestionVariantRepository extends JpaRepository<FaqQuestionVariant, Long> {
    List<FaqQuestionVariant> findByOriginFaqIdOrderByIdAsc(Long faqId);

    boolean existsByOriginFaqIdAndQuestion(Long faqId, String question);

    @Query("select v from FaqQuestionVariant v where v.originFaq.id in :faqIds order by v.originFaq.id, v.id")
    List<FaqQuestionVariant> findByOriginFaqIdsOrderByFaqIdAndId(@Param("faqIds") List<Long> faqIds);

    @Query(value = "select v from FaqQuestionVariant v join fetch v.originFaq f where f.deletedAt is null",
            countQuery = "select count(v) from FaqQuestionVariant v join v.originFaq f where f.deletedAt is null")
    Page<FaqQuestionVariant> findAllActive(Pageable pageable);
}
