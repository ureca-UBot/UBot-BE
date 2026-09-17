package com.ubot.faq.repository;

import com.ubot.faq.entity.Faq;
import com.ubot.faq.entity.FaqCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FaqCategoryRepository extends JpaRepository<FaqCategory, Long> {
	Optional<FaqCategory> findByName(String category);
}
