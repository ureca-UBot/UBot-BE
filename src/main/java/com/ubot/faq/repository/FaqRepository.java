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

	//삭제된 FAQ도 포함해서, 삭제된 FAQ를 복구했을때 Category가 없는 경우를 방지
	Page<Faq> findAllByFaqCategoryId(Long faqCategoryId, Pageable pageable);

	//삭제된 FAQ도 포함해서, 삭제된 FAQ를 복구했을때 Category가 없는 경우를 방지
	boolean existsAllByFaqCategoryIdAndDeletedAtIsNull(Long faqCategoryId);

	@Query("""
		select f
		from Faq f
		where f.deletedAt is not null and f.id IN :faqIds
""")
	List<Faq> findDeletedFaqByFaqIds(@Param("faqIds") List<Long> faqIds);
}