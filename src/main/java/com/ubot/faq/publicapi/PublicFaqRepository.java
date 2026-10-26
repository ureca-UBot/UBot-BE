package com.ubot.faq.publicapi;
import com.ubot.faq.entity.Faq;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// 공개 조회 전용입니다. 관리자 카테고리와 FAQ 원본을 변경하지 않습니다.
public interface PublicFaqRepository extends Repository<Faq, Long> {
    String CATEGORY = "coalesce(nullif(btrim(split_part(c.name, '/', 1)), ''), btrim(c.name))";
    String FROM_ACTIVE = " from faq f join faq_category c on c.id=f.category_id where f.deleted_at is null and c.deleted_at is null ";
    interface CategoryRow { String getName(); long getCount(); }
    interface FaqRow { Long getId(); String getCategory(); String getQuestion(); String getAnswer(); }
    @Query(value = "select " + CATEGORY + " as name, count(f.id) as count from faq_category c left join faq f on f.category_id=c.id and f.deleted_at is null where c.deleted_at is null group by " + CATEGORY + " order by min(c.id), " + CATEGORY, nativeQuery = true)
    List<CategoryRow> categories();
    @Query(value = "select f.id as id, " + CATEGORY + " as category, f.question as question" + FROM_ACTIVE + "and (cast(:category as text) is null or " + CATEGORY + "=:category) order by f.id asc",
        countQuery = "select count(*)" + FROM_ACTIVE + "and (cast(:category as text) is null or " + CATEGORY + "=:category)", nativeQuery = true)
    Page<FaqRow> list(@Param("category") String category, Pageable pageable);
    @Query(value = "select f.id as id, " + CATEGORY + " as category, f.question as question, f.answer as answer" + FROM_ACTIVE + "and f.id=:id", nativeQuery = true)
    Optional<FaqRow> detail(@Param("id") Long id);
}
