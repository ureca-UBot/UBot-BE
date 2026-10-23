package com.ubot.guardreview.scheduler;

import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import com.ubot.guardreview.service.ReviewValidationService;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 기존 AFTER_COMMIT 갱신을 유지하며 다른 API·Worker 프로세스도 변경을 읽게 합니다. */
@Component
@Slf4j
@RequiredArgsConstructor
public class ReviewWordCacheRefresh {
	private final ReviewValidationService validator;
	private final ForbiddenWordFilterService filter;
	private Set<String> last;
	@Scheduled(fixedDelay = 5000)
	public void refresh() {
		try {
			Set<String> current = validator.currentWords();
			if (!current.equals(last)) {
				filter.refreshForbiddenWordCache();
				last = current;
			}
		} catch (RuntimeException e) {
			log.warn("금칙어 갱신을 다음 주기에 다시 확인합니다: 오류유형={}", e.getClass().getSimpleName());
		}
	}
}
