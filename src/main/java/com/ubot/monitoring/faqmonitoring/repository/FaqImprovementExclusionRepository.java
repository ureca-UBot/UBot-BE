package com.ubot.monitoring.faqmonitoring.repository;

import com.ubot.monitoring.faqmonitoring.entity.FaqImprovementExclusion;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface FaqImprovementExclusionRepository extends JpaRepository<FaqImprovementExclusion, Long> {

	@Modifying
	@Query(value = """
			INSERT INTO faq_improvement_exclusion (
				faq_id, improvement_type, faq_version, memo, excluded_by
			)
			VALUES (
				:faqId, :improvementType, :faqVersion, :memo, :excludedBy
			)
			ON CONFLICT (faq_id, improvement_type, faq_version)
				WHERE released_at IS NULL
			DO NOTHING
			""", nativeQuery = true)
	int insertActiveIfAbsent(
			@Param("faqId") Long faqId,
			@Param("improvementType") String improvementType,
			@Param("faqVersion") int faqVersion,
			@Param("memo") String memo,
			@Param("excludedBy") Long excludedBy
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
