package com.ubot.faq.repository;

import com.ubot.faq.entity.FaqCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FaqCategoryRepository extends JpaRepository<FaqCategory, Long> {
	Optional<FaqCategory> findByNameAndDeletedAtIsNull(String category);

	@Query("""
		select fc
		from FaqCategory fc
		where lower(fc.name) like lower(concat('%', :keyword, '%'))
		and fc.deletedAt is null
		"""
	)
	Page<FaqCategory> findByKeywordAndDeletedAtIsNull(String keyword, Pageable pageable);

	Optional<FaqCategory> findByIdAndDeletedAtIsNull(Long faqCategoryId);

	Page<FaqCategory> findByDeletedAtIsNull(Pageable pageable);
}