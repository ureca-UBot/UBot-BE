package com.ubot.chat.service;

import com.ubot.faq.repository.FaqRepository;
import com.ubot.faq.repository.FaqRepository.EnglishWord;
import com.ubot.faq.service.FaqChangedEvent;
import com.ubot.prompt.exception.PromptException;
import com.ubot.prompt.service.SubQuestionPromptService;
import java.util.Locale;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 판정 프롬프트의 영문만 검사 대상으로 삼고, 등록된 FAQ에서 쓰는 영문은 허용합니다. */
@Component
@RequiredArgsConstructor
@Slf4j
class SubQuestionEnglishPolicy {
	private final FaqRepository faqRepository;
	private final SubQuestionPromptService promptService;
	private final Map<Long, Set<String>> wordsByFaq = new HashMap<>();
	private final Map<String, Integer> faqCountByWord = new HashMap<>();
	private volatile Vocabulary vocabulary = new Vocabulary(Set.of(), Set.of());

	/** 서버의 변경 알림 구독이 연결될 때 초기화하고, 재연결 때는 놓친 변경분을 다시 맞춥니다. */
	public synchronized void initialize() {
		try {
			Set<String> promptWords = promptService.englishWords();
			List<EnglishWord> registeredWords = faqRepository.findActiveEnglishWords(null);
			wordsByFaq.clear();
			faqCountByWord.clear();
			for (EnglishWord word : registeredWords) {
				wordsByFaq.computeIfAbsent(word.getFaqId(), ignored -> new HashSet<>()).add(word.getWord());
			}
			wordsByFaq.values().forEach(words -> words.forEach(word -> faqCountByWord.merge(word, 1, Integer::sum)));
			vocabulary = new Vocabulary(promptWords, Set.copyOf(faqCountByWord.keySet()));
			log.info("하위 질문 영문 검사 목록을 갱신했습니다: 프롬프트영문수={}, FAQ영문수={}",
					vocabulary.promptWords().size(), vocabulary.faqWords().size());
		} catch (DataAccessException | PromptException exception) {
			log.warn("하위 질문 영문 검사 목록을 갱신하지 못해 기존 목록을 유지합니다", exception);
		}
	}

	/** 변경 알림을 받은 FAQ만 새 트랜잭션으로 읽습니다. */
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public synchronized void onFaqChanged(FaqChangedEvent event) {
		for (Long faqId : event.faqIds()) {
			try {
				Set<String> updatedWords = new HashSet<>();
				faqRepository.findActiveEnglishWords(faqId).forEach(word -> updatedWords.add(word.getWord()));
				Set<String> previous = wordsByFaq.getOrDefault(faqId, Set.of());
				for (String word : previous) {
					faqCountByWord.computeIfPresent(word, (key, count) -> count == 1 ? null : count - 1);
				}
				if (updatedWords.isEmpty()) {
					wordsByFaq.remove(faqId);
				} else {
					wordsByFaq.put(faqId, updatedWords);
					updatedWords.forEach(word -> faqCountByWord.merge(word, 1, Integer::sum));
				}
				vocabulary = new Vocabulary(vocabulary.promptWords(), Set.copyOf(faqCountByWord.keySet()));
			} catch (DataAccessException exception) {
				log.warn("변경된 FAQ의 영문 허용 목록을 갱신하지 못했습니다: FAQID={}", faqId, exception);
			}
		}
	}

	boolean requiresSource(String word) {
		Vocabulary snapshot = vocabulary;
		String normalized = word.toLowerCase(Locale.ROOT);
		return snapshot.promptWords().contains(normalized) && !snapshot.faqWords().contains(normalized);
	}

	private record Vocabulary(Set<String> promptWords, Set<String> faqWords) {
	}
}
