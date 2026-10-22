package com.ubot.monitoring.faqmonitoring.repository;

import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.FaqImprovementType;
import com.ubot.monitoring.faqmonitoring.entity.FaqImprovementExclusion;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface FaqImprovementExclusionRepository extends JpaRepository<FaqImprovementExclusion, Long> {

	boolean existsByFaqIdAndImprovementTypeAndFaqVersionAndReleasedAtIsNull(
			Long faqId,
			FaqImprovementType improvementType,
			int faqVersion
	);

	Optional<FaqImprovementExclusion> findByIdAndReleasedAtIsNull(Long exclusionId);

	@Query(
			value = """
					select exclusion
					from FaqImprovementExclusion exclusion
					join fetch exclusion.faq faq
					left join fetch exclusion.excludedBy
					where exclusion.releasedAt is null
						and faq.deletedAt is null
					""",
			countQuery = """
					select count(exclusion)
					from FaqImprovementExclusion exclusion
					join exclusion.faq faq
					where exclusion.releasedAt is null
						and faq.deletedAt is null
					"""
	)
	Page<FaqImprovementExclusion> findAllUnreleased(Pageable pageable);

	@Query(
			value = """
					select exclusion
					from FaqImprovementExclusion exclusion
					join fetch exclusion.faq faq
					left join fetch exclusion.excludedBy
					left join fetch exclusion.releasedBy
					where exclusion.releasedAt is not null
						and faq.deletedAt is null
					""",
			countQuery = """
					select count(exclusion)
					from FaqImprovementExclusion exclusion
					join exclusion.faq faq
					where exclusion.releasedAt is not null
						and faq.deletedAt is null
					"""
	)
	Page<FaqImprovementExclusion> findAllReleased(Pageable pageable);
}
