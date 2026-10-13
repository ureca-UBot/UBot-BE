package com.ubot.faq.repository;

import com.ubot.faq.entity.Faq;
import com.ubot.faq.enums.Intent;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FaqRepository extends JpaRepository<Faq, Long> {
    // Scalar projection: an entity loaded before embedding must not hide a concurrent FAQ edit.
    @Query("""
            select f.id as id, f.question as question, f.answer as answer,
                   f.version as version, f.intent as intent, f.cacheable as cacheable
            from Faq f where f.id = :faqId and f.deletedAt is null
            """)
    Optional<CacheRegistrationSnapshot> findActiveCacheRegistrationSnapshotById(@Param("faqId") Long faqId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from Faq f where f.id = :faqId and f.deletedAt is null")
    Optional<Faq> findActiveByIdForUpdate(@Param("faqId") Long faqId);

    interface CacheRegistrationSnapshot {
        Long getId();
        String getQuestion();
        String getAnswer();
        Integer getVersion();
        Intent getIntent();
        boolean isCacheable();
    }

	@Query("""
		select f
		from Faq f
		where f.deletedAt is null and f.id = :faqId
""")
	Optional<Faq> findActiveById(Long faqId);

	@Query("""
		select f
		from Faq f
		where f.deletedAt is null
		and lower(f.question) like lower(concat('%', :keyword, '%'))
		and (
			:categoryId is null
			or
			f.faqCategory.id = :categoryId
		)
""")
	Page<Faq> findAllActives(Pageable pageable, @Param("keyword") String keyword, @Param("categoryId") Long categoryId);

	@Query("""
		select f
		from Faq f
		where f.deletedAt is not null
""")
	Page<Faq> findAllDeletedFaq(Pageable pageable);

	Page<Faq> findAllByDeletedAtIsNull(Pageable pageable);
	Page<Faq> findAllByFaqCategoryIdAndDeletedAtIsNull(Long faqCategoryId, Pageable pageable);

	boolean existsAllByFaqCategoryIdAndDeletedAtIsNull(Long faqCategoryId);

	@Query("""
		select f
		from Faq f
		where f.deletedAt is not null and f.id IN :faqIds
""")
	List<Faq> findDeletedFaqByFaqIds(@Param("faqIds") List<Long> faqIds);
}
