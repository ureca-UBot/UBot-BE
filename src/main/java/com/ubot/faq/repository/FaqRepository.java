package com.ubot.faq.repository;

import com.ubot.faq.entity.Faq;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
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
	List<Faq> findActivesByKeyword(String keyword);

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
	List<Faq> findAllActives();

	@Query("""
		select f
		from Faq f
		where f.deletedAt is not null
""")
	List<Faq> findAllDeletedFaq();
}
