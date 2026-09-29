package com.ubot.forbiddenword.repository;

import com.ubot.forbiddenword.entity.ForbiddenWord;
import com.ubot.forbiddenword.enums.ForbiddenWordStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ForbiddenWordRepository extends JpaRepository<ForbiddenWord, Long> {
	List<ForbiddenWord> findAllByStatus(ForbiddenWordStatus status);

	boolean existsByWord(String word);

	boolean existsByWordAndIdNot(String word, Long id);
}
