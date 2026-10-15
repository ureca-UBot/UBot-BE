package com.ubot.faq.repository;

import com.ubot.faq.entity.FaqQuestionVariant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface FaqQuestionVariantRepository extends JpaRepository<FaqQuestionVariant, Long> {
    List<FaqQuestionVariant> findByOriginFaqIdOrderByIdAsc(Long faqId);

    @Query(value = "select v from FaqQuestionVariant v join fetch v.originFaq f where f.deletedAt is null",
            countQuery = "select count(v) from FaqQuestionVariant v join v.originFaq f where f.deletedAt is null")
    Page<FaqQuestionVariant> findAllActive(Pageable pageable);
}
