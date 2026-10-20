package com.ubot.faq.repository;

import com.ubot.faq.entity.Faq;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FaqRepository extends JpaRepository<Faq, Long> {
	/** 활성 FAQ의 원문 질문·답변과 질문 표현에 실제로 쓰인 영문 토큰입니다. */
	@Query(value = """
		select distinct texts.faq_id as "faqId", lower((matched.word)[1]) as word
		from (
			select id as faq_id, concat(question, ' ', answer) as content from faq
			where deleted_at is null and (cast(:faqId as bigint) is null or id = :faqId)
			union all
			select f.id, v.question from faq_question_variants v
			join faq f on f.id = v.origin_faq_id
			where f.deleted_at is null and (cast(:faqId as bigint) is null or f.id = :faqId)
		) texts
		cross join lateral regexp_matches(texts.content, '([A-Za-z]+)', 'g') as matched(word)
		""", nativeQuery = true)
	List<EnglishWord> findActiveEnglishWords(@Param("faqId") Long faqId);

	interface EnglishWord {
		Long getFaqId();
		String getWord();
	}

	@Query("""
		select f
		from Faq f
		where f.deletedAt is null and f.id = :faqId
""")
	Optional<Faq> findActiveById(Long faqId);

	boolean existsByIdAndDeletedAtIsNull(Long faqId);

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
