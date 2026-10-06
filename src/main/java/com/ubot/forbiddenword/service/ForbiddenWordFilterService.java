package com.ubot.forbiddenword.service;

import com.ubot.forbiddenword.entity.ForbiddenWord;
import com.ubot.forbiddenword.enums.ForbiddenWordStatus;
import com.ubot.forbiddenword.exception.ForbiddenWordErrorCode;
import com.ubot.forbiddenword.exception.ForbiddenWordException;
import com.ubot.forbiddenword.repository.ForbiddenWordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.stream.Collectors;

/** 활성 금지어를 로컬 메모리에 캐시하고 사용자 입력의 금지어 포함 여부를 검사합니다. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ForbiddenWordFilterService {
	private final ForbiddenWordRepository forbiddenWordRepository;

	// 갱신 시 새 Set으로 통째로 교체하므로 읽는 쪽은 락 없이 참조만 사용합니다.
	private volatile Set<String> activeForbiddenWords = Set.of();

	@EventListener(ApplicationReadyEvent.class)
	public void initializeForbiddenWordCache() {
		refreshForbiddenWordCache();
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onForbiddenWordChanged(ForbiddenWordChangedEvent event) {
		refreshForbiddenWordCache();
	}

	// 동시에 커밋된 변경의 갱신이 겹치면 먼저 읽은 옛 목록이 나중에 덮어쓸 수 있어 한 번에 하나씩 갱신합니다.
	public synchronized void refreshForbiddenWordCache() {
		activeForbiddenWords = forbiddenWordRepository.findAllByStatus(ForbiddenWordStatus.ACTIVE).stream()
				.map(ForbiddenWord::getWord)
				.collect(Collectors.toUnmodifiableSet());
		log.info("금칙어 캐시를 갱신했습니다: 활성금칙어수={}", activeForbiddenWords.size());
	}

	public void validateForbiddenWord(String content) {
		if (content == null || content.isEmpty()) {
			return;
		}
		for (String forbiddenWord : activeForbiddenWords) {
			if (content.contains(forbiddenWord)) {
				log.warn("금칙어가 포함된 입력을 차단했습니다: 입력길이={}", content.length());
				// 탐지된 단어는 응답에 노출하지 않습니다.
				throw new ForbiddenWordException(ForbiddenWordErrorCode.FORBIDDEN_WORD_DETECTED);
			}
		}
	}
}
