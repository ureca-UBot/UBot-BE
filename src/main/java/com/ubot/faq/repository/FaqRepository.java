package com.ubot.faq.repository;

import com.ubot.faq.entity.Faq;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FaqRepository extends JpaRepository<Faq, Long> {
	@Query("""
		select f
		from Faq f
		where f.deletedAt is null and (
			lower(f.question) like lower(concat('%', :keyword, '%'))
		or	lower(f.answer) like lower(concat('%', :keyword, '%'))
		)
""")
	Page<Faq> findActivesByKeyword(String keyword, Pageable pageable);

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
""")
	Page<Faq> findAllActives(Pageable pageable);

	@Query("""
		select f
		from Faq f
		where f.deletedAt is not null
""")
	Page<Faq> findAllDeletedFaq(Pageable pageable);

	//삭제된 FAQ도 포함해서, 삭제된 FAQ를 복구했을때 Category가 없는 경우를 방지
	Page<Faq> findAllByFaqCategoryId(Long faqCategoryId, Pageable pageable);

	//삭제된 FAQ도 포함해서, 삭제된 FAQ를 복구했을때 Category가 없는 경우를 방지
	boolean existsAllByFaqCategoryId(Long faqCategoryId);
}