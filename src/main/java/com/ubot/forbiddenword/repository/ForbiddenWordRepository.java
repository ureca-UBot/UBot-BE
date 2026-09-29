package com.ubot.forbiddenword.repository;

import com.ubot.forbiddenword.entity.ForbiddenWord;
import com.ubot.forbiddenword.enums.ForbiddenWordStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ForbiddenWordRepository extends JpaRepository<ForbiddenWord, Long> {
	List<ForbiddenWord> findAllByStatus(ForbiddenWordStatus status);

	boolean existsByWord(String word);

	boolean existsByWordAndIdNot(String word, Long id);

	// 수정 중인 금지어 행을 잠가 다른 관리자의 수정은 커밋 이후 최신 값을 읽도록 합니다.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select fw from ForbiddenWord fw where fw.id = :id")
	Optional<ForbiddenWord> findByIdForUpdate(@Param("id") Long id);
}
