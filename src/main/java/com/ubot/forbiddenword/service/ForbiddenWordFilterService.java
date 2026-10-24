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

import java.util.Locale;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 활성 금지어를 로컬 메모리에 캐시하고 사용자 입력의 금지어 포함 여부를 검사합니다. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ForbiddenWordFilterService {
	private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
	private static final String KOREAN_WORD_ENDING =
			"(?:입니다|입니까|이에요|예요|이?라고(?:요)?|이?라는|이야|[아야은는이가을를도만])?";

	// bad는 영문 단어 내부와 명확한 HTTP 오류명에서 구분합니다. 다른 일치 항목은 계속 검사합니다.
	private static final Pattern ENGLISH_BAD_WORD = Pattern.compile(
			"(?<![a-z])bad(?![a-z])"
					+ "(?!\\s+request(?![a-z])\\s*(?:오류|에러|error)(?![a-z])"
					+ "|(?<=400\\s{1,2}bad)\\s+request(?![a-z]))");

	private final ForbiddenWordRepository forbiddenWordRepository;

	// 갱신 시 새 Set으로 통째로 교체하므로 읽는 쪽은 락 없이 참조만 사용합니다.
	private volatile Set<String> activeForbiddenWords = Set.of();

	/** AI 검사도 DB를 다시 조회하지 않고 코드 검사와 같은 불변 캐시를 사용합니다. */
	public java.util.List<String> getActiveForbiddenWords() {
		return activeForbiddenWords.stream().sorted().toList();
	}

	/** AI 자료에 등장한 활성 금칙어만 반환합니다. 차단·로그·DB 조회 없이 기존 매칭 규칙을 사용합니다. */
	public List<String> findMatchingForbiddenWords(List<String> contents) {
		if (contents == null || contents.isEmpty()) return List.of();
		List<InspectionText> texts = contents.stream().filter(Objects::nonNull)
				.filter(text -> !text.isEmpty()).distinct()
				.map(text -> new InspectionText(text, normalizeForInspection(text))).toList();
		return activeForbiddenWords.stream()
				.filter(word -> texts.stream().anyMatch(text -> containsForbiddenWord(text.original(), text.normalized(), word)))
				.sorted().toList();
	}

	/** 검토용 독립 인스턴스입니다. 운영 캐시와 DB에는 쓰지 않습니다. */
	public ForbiddenWordFilterService preview(Set<String> words) {
		ForbiddenWordFilterService preview = new ForbiddenWordFilterService(forbiddenWordRepository);
		preview.activeForbiddenWords = Set.copyOf(words);
		return preview;
	}

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
		String inspectionText = normalizeForInspection(content);
		for (String forbiddenWord : activeForbiddenWords) {
			if (containsForbiddenWord(content, inspectionText, forbiddenWord)) {
				log.warn("금칙어가 포함된 입력을 차단했습니다: 입력길이={}", content.length());
				// 탐지된 단어는 응답에 노출하지 않습니다.
				throw new ForbiddenWordException(ForbiddenWordErrorCode.FORBIDDEN_WORD_DETECTED);
			}
		}
	}

	private boolean containsForbiddenWord(String content, String inspectionText, String forbiddenWord) {
		String inspectionWord = normalizeForInspection(forbiddenWord);
		boolean containsWord = "bad".equals(inspectionWord)
				? ENGLISH_BAD_WORD.matcher(inspectionText).find()
				: content.contains(forbiddenWord) || (!inspectionWord.isEmpty() && inspectionText.contains(inspectionWord));
		return containsWord || (!inspectionWord.isEmpty() && containsSpacedVariant(inspectionText, inspectionWord));
	}

	private record InspectionText(String original, String normalized) { }

	// 원문은 유지하고 검사용 복사본에서만 숨은 서식 문자·전각 ASCII·대소문자를 정리합니다.
	private String normalizeForInspection(String content) {
		StringBuilder normalized = new StringBuilder(content.length());
		content.codePoints()
				.filter(codePoint -> Character.getType(codePoint) != Character.FORMAT)
				.forEach(codePoint -> normalized.appendCodePoint(
						codePoint >= 0xFF01 && codePoint <= 0xFF5E ? codePoint - 0xFEE0 : codePoint));
		return WHITESPACE.matcher(normalized.toString().toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
	}

	private boolean containsSpacedVariant(String inspectionText, String inspectionWord) {
		String compactWord = inspectionWord.replace(" ", "");
		if (!inspectionText.contains(" ") || compactWord.codePointCount(0, compactWord.length()) < 2) {
			return false;
		}
		String spacedWord = compactWord.codePoints()
				.mapToObj(codePoint -> Pattern.quote(new String(Character.toChars(codePoint))))
				.collect(Collectors.joining("\\s*"));
		// 정상 단어 사이를 연결하지 않고, 금지어 뒤의 "야"·"입니다"·"라고" 같은 조사·어미도 비교합니다.
		Matcher spacedVariant = Pattern.compile("(?<![\\p{L}\\p{N}_])(" + spacedWord + ")"
				+ KOREAN_WORD_ENDING + "(?![\\p{L}\\p{N}_])", Pattern.UNICODE_CHARACTER_CLASS)
				.matcher(inspectionText);
		while (spacedVariant.find()) {
			// 띄어쓰기 변형 검사에서는 단어 내부에 공백이 실제로 있는 일치만 판정합니다.
			if (spacedVariant.group(1).contains(" ")) {
				return true;
			}
		}
		return false;
	}
}
