package com.ubot.faq.publicapi;

import com.ubot.faq.entity.Faq;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** 공개 조회 전용입니다. 관리자 카테고리와 FAQ 원본을 변경하지 않습니다. */
public interface PublicFaqRepository extends Repository<Faq, Long> {

	/** 카테고리 이름의 첫 '/' 앞부분을 공개 카테고리로 씁니다. 앞부분이 비면 이름 전체를 씁니다. */
	String PUBLIC_CATEGORY = "coalesce(nullif(btrim(split_part(c.name, '/', 1)), ''), btrim(c.name))";

	/** 삭제되지 않은 FAQ 중 카테고리도 삭제되지 않은 것만 공개합니다. */
	String ACTIVE_FAQS = """
			 from faq f
			 join faq_category c on c.id = f.category_id
			 where f.deleted_at is null and c.deleted_at is null
			""";

	/** category가 null이면 전체, 아니면 그 공개 카테고리의 FAQ입니다. */
	String CATEGORY_FILTER = " and (cast(:category as text) is null or " + PUBLIC_CATEGORY + " = :category)";

	interface CategoryRow {
		String getName();

		long getCount();
	}

	interface FaqRow {
		Long getId();

		String getCategory();

		String getQuestion();

		String getAnswer();
	}

	/** 공개 카테고리별 FAQ 수입니다. FAQ가 없는 카테고리도 0건으로 포함하며, 먼저 만든 카테고리 순서입니다. */
	@Query(nativeQuery = true, value = "select " + PUBLIC_CATEGORY + " as name, count(f.id) as count"
			+ " from faq_category c"
			+ " left join faq f on f.category_id = c.id and f.deleted_at is null"
			+ " where c.deleted_at is null"
			+ " group by " + PUBLIC_CATEGORY
			+ " order by min(c.id), " + PUBLIC_CATEGORY)
	List<CategoryRow> categories();

	@Query(nativeQuery = true,
			value = "select f.id as id, " + PUBLIC_CATEGORY + " as category, f.question as question"
					+ ACTIVE_FAQS + CATEGORY_FILTER
					+ " order by f.id asc",
			countQuery = "select count(*)" + ACTIVE_FAQS + CATEGORY_FILTER)
	Page<FaqRow> list(@Param("category") String category, Pageable pageable);

	@Query(nativeQuery = true,
			value = "select f.id as id, " + PUBLIC_CATEGORY + " as category, f.question as question, f.answer as answer"
					+ ACTIVE_FAQS
					+ " and f.id = :id")
	Optional<FaqRow> detail(@Param("id") Long id);
}
