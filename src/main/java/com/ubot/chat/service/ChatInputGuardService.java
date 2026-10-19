package com.ubot.chat.service;

import com.ubot.ai.dto.ConversationTurn;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.common.GlobalException;
import com.ubot.forbiddenword.exception.ForbiddenWordErrorCode;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 질문 원문을 보존하고 기존 입력 검사에 통과한 요청만 처리용 질문으로 준비합니다. */
@Service
@RequiredArgsConstructor
public class ChatInputGuardService {

	private final ForbiddenWordFilterService forbiddenWordFilterService;
	private final ChatPromptInjectionService chatPromptInjectionService;

	private static final Pattern GRAPHEME = Pattern.compile("\\X");
	private static final Pattern GREETING = Pattern.compile(
			"(?:안녕하십니까|안녕하세요|안녕|하이|hello|hi)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	private static final Pattern REQUEST_ENDING = Pattern.compile(
			"(?<![\\p{L}\\p{N}_])[\\p{IsHangul}]{0,30}"
					+ "(?:주세요|주실래(?:요)?|줄래(?:요)?|줘(?:요)?|나요|습니까|세요|해)(?![\\p{L}\\p{N}_])");
	private static final Pattern CONVERSATIONAL_BOUNDARY = Pattern.compile(
			REQUEST_ENDING.pattern()
					+ "(?<gap>[\\p{Zs}\\t]+(?:(?:그리고|그런데|근데|또|그다음에)[\\p{Zs}\\t]+)?)(?=\\S)");
	private static final Pattern DEPENDENT_REQUEST = Pattern.compile(
			"(?:그\\s*(?:거|것|내용|정보|조건|대신)|이\\s*(?:것|내용)|위\\s*(?:내용|정보)|앞(?:의|에서)"
					+ "|그대로|대신|그래서|그러면|하지만|라고|라는|주(?:지|면|는)|줘도)");
	private static final Pattern REPORTED_SPEECH = Pattern.compile(
			"(?:라고|고)\\s*(?:말했|요구했|요청했|했)(?:는데|어요|습니다)[.!?。！？]?\\s*$");
	private static final Pattern REPORT_CONTINUATION = Pattern.compile(
			"(?:실행|수행|처리)(?:하면\\s*안\\s*(?:돼|됩니다)|하지\\s*마(?:세요)?)[.!?。！？]?\\s*$");
	private static final Pattern PROTECTED_CONTENT = Pattern.compile(
			"(?<quote>```.*?(?:```|$)|`[^`]*(?:`|$)"
					+ "|\"(?:\\\\.|[^\"\\\\])*(?:\"|$)"
					+ "|(?<![\\p{L}\\p{N}_])'(?:\\\\.|[^'\\\\])*(?:'|$)"
					+ "|“[^”]*(?:”|$)|‘[^’]*(?:’|$)|\\([^)]*(?:\\)|$)|\\[[^]]*(?:\\]|$))"
					+ "|https?://[^\\s<>\"‘’“”]+", Pattern.DOTALL | Pattern.UNICODE_CHARACTER_CLASS);
	private static final String SENTENCE_BOUNDARIES = ".!?;。！？\r\n";
	private static final String GREETING_SEPARATORS = ",.!?~，。！？～";
	private static final int EMOJI_STYLE = 0xFE0F;
	private static final int TEXT_STYLE = 0xFE0E;
	private static final int KEYCAP = 0x20E3;

	/** 독립된 요청 중 차단된 요청만 제거하며, 경계를 걸쳐 조합된 공격도 기존 검사로 다시 확인합니다. */
	public String prepareQuestion(String question) {
		List<String> requests = new ArrayList<>();
		String previousRequest = null;
		for (String request : prepareCandidates(question)) {
			if (isAllowedRequest(request, previousRequest)) {
				requests.add(request);
				previousRequest = request;
			} else {
				// 제거된 요청을 건너뛰어 오래된 기기 문맥을 이어 붙이지 않습니다.
				previousRequest = null;
			}
		}
		if (requests.isEmpty()) {
			throw new ChatException(ChatErrorCode.NO_USABLE_QUESTION);
		}
		removeCombinedAttacks(requests);
		return String.join("\n", requests);
	}

	/** DB 원문은 바꾸지 않고, 판정·답변에 전달할 이전 사용자 질문만 기존 입력 검사로 정리합니다. */
	public List<ConversationTurn> prepareHistory(List<ConversationTurn> history) {
		if (history == null || history.isEmpty()) {
			return List.of();
		}
		List<ConversationTurn> prepared = new ArrayList<>();
		for (ConversationTurn turn : history) {
			try {
				prepared.add(new ConversationTurn(prepareQuestion(turn.question()), turn.answer()));
			} catch (GlobalException exception) {
				// 탐지된 요청만 있는 턴은 답변과 함께 제외하며, 검사 기능의 다른 오류는 숨기지 않습니다.
				if (!isInputDetection(exception) && exception.getErrorCode() != ChatErrorCode.NO_USABLE_QUESTION) {
					throw exception;
				}
			}
		}
		return List.copyOf(prepared);
	}

	/** 합쳐야 탐지되는 공격의 구성 요청만 제거하고, 남은 정상 요청을 다시 검사합니다. */
	private void removeCombinedAttacks(List<String> requests) {
		while (!requests.isEmpty()) {
			try {
				validateCombinedRequests(requests);
				return;
			} catch (GlobalException exception) {
				if (!isInputDetection(exception)) {
					throw exception;
				}
				List<Integer> attackIndexes = new ArrayList<>();
				for (int index = 0; index < requests.size(); index++) {
					attackIndexes.add(index);
				}
				// 큰 묶음부터 제외해 보며, 공격 탐지에 필요하지 않은 정상 요청은 보존합니다.
				int partitions = 2;
				while (attackIndexes.size() > 1) {
					int partitionSize = (attackIndexes.size() + partitions - 1) / partitions;
					boolean reduced = false;
					for (int start = 0; start < attackIndexes.size(); start += partitionSize) {
						List<Integer> candidateIndexes = new ArrayList<>(attackIndexes);
						candidateIndexes.subList(start, Math.min(start + partitionSize, attackIndexes.size())).clear();
						if (!candidateIndexes.isEmpty()
								&& !isAllowedCombinedRequests(candidateIndexes.stream().map(requests::get).toList())) {
							attackIndexes = candidateIndexes;
							partitions = Math.max(2, partitions - 1);
							reduced = true;
							break;
						}
					}
					if (!reduced) {
						if (partitions >= attackIndexes.size()) {
							break;
						}
						partitions = Math.min(attackIndexes.size(), partitions * 2);
					}
				}
				if (attackIndexes.size() == requests.size()) {
					// 정상 요청이 없으면 기존 탐지 오류로 종료하며 공격 조각은 전달하지 않습니다.
					throw exception;
				}
				for (int index = attackIndexes.size() - 1; index >= 0; index--) {
					requests.remove(attackIndexes.get(index).intValue());
				}
			}
		}
	}

	private boolean isAllowedCombinedRequests(List<String> requests) {
		try {
			validateCombinedRequests(requests);
			return true;
		} catch (GlobalException exception) {
			if (isInputDetection(exception)) {
				return false;
			}
			throw exception;
		}
	}

	private void validateCombinedRequests(List<String> requests) {
		forbiddenWordFilterService.validateForbiddenWord(String.join("\n", requests));
		chatPromptInjectionService.validatePreparedRequests(requests);
	}

	private boolean isInputDetection(GlobalException exception) {
		return exception.getErrorCode() == ForbiddenWordErrorCode.FORBIDDEN_WORD_DETECTED
				|| exception.getErrorCode() == ChatErrorCode.PROMPT_INJECTION_DETECTED;
	}

	private boolean isAllowedRequest(String request, String previousRequest) {
		try {
			validateRequest(request, previousRequest);
			return true;
		} catch (GlobalException exception) {
			// 탐지 결과만 요청 제거로 처리합니다. 검사 기능의 다른 업무 오류는 숨기지 않습니다.
			if (isInputDetection(exception)) {
				return false;
			}
			throw exception;
		}
	}

	private void validateRequest(String request, String previousRequest) {
		forbiddenWordFilterService.validateForbiddenWord(request);
		if (previousRequest == null) {
			chatPromptInjectionService.validatePromptInjection(request);
		} else {
			chatPromptInjectionService.validatePromptInjection(request, previousRequest);
		}
	}

	/**
	 * 정리·분리만 수행합니다. 실제 전달용 질문은 {@link #prepareQuestion(String)}에서 기존 검사를 거칩니다.
	 * 제거된 이모지 뒤에 붙은 인사도 구분하도록, 분리 위치를 원문에 대응시켜 각 요청의 시작을 확인합니다.
	 */
	public List<String> prepareCandidates(String question) {
		if (question == null || question.isBlank()) {
			return List.of();
		}
		PreparedText inspected = removeEmoji(question);
		List<String> candidates = new ArrayList<>();
		for (RequestRange range : splitRequests(inspected.text())) {
			String originalRequest = question.substring(inspected.offsets()[range.start()], inspected.offsets()[range.end()]);
			String candidate = removeEmoji(stripLeadingGreetings(originalRequest)).text().strip();
			if (!candidate.isBlank()) {
				candidates.add(candidate);
			}
		}
		return List.copyOf(candidates);
	}

	private PreparedText removeEmoji(String text) {
		StringBuilder cleaned = new StringBuilder(text.length());
		int[] offsets = new int[text.length() + 1];
		Matcher grapheme = GRAPHEME.matcher(text);
		while (grapheme.find()) {
			String cluster = grapheme.group();
			int first = cluster.codePointAt(0);
			// Emoji 속성에는 0~9와 #·*도 포함되므로 키캡 장식만 제거하고 본문 기호·숫자는 보존합니다.
			if (isKeycapBase(first) && (cluster.indexOf(KEYCAP) >= 0 || cluster.indexOf(EMOJI_STYLE) >= 0)) {
				offsets[cleaned.length()] = grapheme.start();
				cleaned.appendCodePoint(first);
			} else if (!isEmojiCluster(cluster)) {
				for (int index = grapheme.start(); index < grapheme.end(); index++) {
					offsets[cleaned.length()] = index;
					cleaned.append(text.charAt(index));
				}
			}
		}
		offsets[cleaned.length()] = text.length();
		return new PreparedText(cleaned.toString(), offsets);
	}

	private boolean isEmojiCluster(String cluster) {
		boolean emojiStyle = cluster.indexOf(EMOJI_STYLE) >= 0;
		boolean textStyle = cluster.indexOf(TEXT_STYLE) >= 0;
		return cluster.codePoints().anyMatch(codePoint -> Character.isEmoji(codePoint) && !isKeycapBase(codePoint)
				&& (Character.isEmojiPresentation(codePoint) || emojiStyle || (!textStyle
						&& Character.getType(codePoint) != Character.MATH_SYMBOL
						&& codePoint != 0x00A9 && codePoint != 0x00AE && codePoint != 0x2122)));
	}

	private boolean isKeycapBase(int codePoint) {
		return codePoint >= '0' && codePoint <= '9' || codePoint == '#' || codePoint == '*';
	}

	private String stripLeadingGreetings(String text) {
		int cursor = skipGreetingSeparators(text, 0);
		boolean foundGreeting = false;
		while (cursor < text.length()) {
			Matcher greeting = GREETING.matcher(text).region(cursor, text.length());
			if (!greeting.lookingAt() || !isGreetingBoundary(text, greeting.end())) {
				break;
			}
			foundGreeting = true;
			cursor = skipGreetingSeparators(text, greeting.end());
		}
		return foundGreeting ? text.substring(cursor) : text;
	}

	private int skipGreetingSeparators(String text, int start) {
		int cursor = start;
		while (cursor < text.length() && isGreetingBoundary(text, cursor)) {
			Matcher grapheme = GRAPHEME.matcher(text).region(cursor, text.length());
			if (!grapheme.lookingAt()) {
				break;
			}
			cursor = grapheme.end();
		}
		return cursor;
	}

	private boolean isGreetingBoundary(String text, int index) {
		if (index == text.length()) {
			return true;
		}
		int codePoint = text.codePointAt(index);
		if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
			return true;
		}
		// 인사 다음의 .5GB 같은 수치를 인사 구분 기호로 지우지 않습니다.
		if (GREETING_SEPARATORS.indexOf(codePoint) >= 0 && !isTechnicalDot(text, index)) {
			return true;
		}
		Matcher grapheme = GRAPHEME.matcher(text).region(index, text.length());
		return grapheme.lookingAt() && isEmojiCluster(grapheme.group());
	}

	private List<RequestRange> splitRequests(String text) {
		TextProtection protection = protectContent(text);
		Map<Integer, Integer> boundaries = new TreeMap<>();
		List<RequestRange> instructionRanges = new ArrayList<>();
		for (ChatPromptInjectionService.InstructionRange instruction : ChatPromptInjectionService.instructionRanges(text)) {
			boolean quoted = protection.quotedRanges().stream().anyMatch(range -> range.start() < instruction.start()
					&& instruction.end() <= range.end());
			if (!quoted) {
				instructionRanges.add(new RequestRange(instruction.start(), instruction.end()));
				Arrays.fill(protection.protectedPositions(), instruction.start(), instruction.end(), true);
				boundaries.put(instruction.start(), instruction.start());
				boundaries.put(instruction.end(), instruction.end());
			}
		}
		Matcher conversation = CONVERSATIONAL_BOUNDARY.matcher(text);
		while (conversation.find()) {
			int start = conversation.start("gap");
			int end = conversation.end("gap");
			if (!protection.protectedPositions()[start] && hasIndependentRequest(text, end, protection)) {
				boundaries.put(start, end);
			}
		}
		for (int index = 0; index < text.length(); index++) {
			if (isSentenceBoundary(text, index, protection)) {
				int end = index + 1;
				while (end < text.length() && isSentenceBoundary(text, end, protection)) {
					end++;
				}
				boundaries.put(end, end);
				index = end - 1;
			}
		}
		List<RequestRange> requests = new ArrayList<>();
		int start = 0;
		for (Map.Entry<Integer, Integer> boundary : boundaries.entrySet()) {
			int end = boundary.getKey();
			boolean instructionBoundary = instructionRanges.stream()
					.anyMatch(range -> range.start() == end || range.end() == end);
			if (end > start && (instructionBoundary || !preservesReportContext(text, start, end, protection))) {
				requests.add(new RequestRange(start, end));
				start = boundary.getValue();
			}
		}
		if (start < text.length()) {
			requests.add(new RequestRange(start, text.length()));
		}
		return requests;
	}

	private TextProtection protectContent(String text) {
		boolean[] protectedPositions = new boolean[text.length()];
		boolean[] quotedPositions = new boolean[text.length()];
		List<RequestRange> quotedRanges = new ArrayList<>();
		Matcher protectedContent = PROTECTED_CONTENT.matcher(text);
		while (protectedContent.find()) {
			Arrays.fill(protectedPositions, protectedContent.start(), protectedContent.end(), true);
			if (protectedContent.group("quote") != null) {
				Arrays.fill(quotedPositions, protectedContent.start(), protectedContent.end(), true);
				quotedRanges.add(new RequestRange(protectedContent.start(), protectedContent.end()));
			}
		}
		return new TextProtection(protectedPositions, quotedPositions, List.copyOf(quotedRanges));
	}

	private boolean hasIndependentRequest(String text, int start, TextProtection protection) {
		if (DEPENDENT_REQUEST.matcher(text).region(start, text.length()).lookingAt()) {
			return false;
		}
		int end = start;
		while (end < text.length() && !isSentenceBoundary(text, end, protection)) {
			end++;
		}
		int endingStart = requestEndingStart(text, start, end, protection);
		// 목적어·주제 없이 "그리고 설명해줘"처럼 앞 요청에 의존하는 표현은 따로 떼지 않습니다.
		return endingStart > start && !text.substring(start, endingStart).isBlank();
	}

	private boolean preservesReportContext(String text, int start, int end, TextProtection protection) {
		if (REPORTED_SPEECH.matcher(text).region(start, end).find()) {
			return true;
		}
		for (int index = start; index < end; index++) {
			if (protection.quotedPositions()[index]) {
				return requestEndingStart(text, start, end, protection) < 0
						|| REPORT_CONTINUATION.matcher(text).region(start, end).find();
			}
		}
		return false;
	}

	private int requestEndingStart(String text, int start, int end, TextProtection protection) {
		Matcher ending = REQUEST_ENDING.matcher(text).region(start, end);
		while (ending.find()) {
			if (!protection.protectedPositions()[ending.start()] && text.substring(ending.end(), end).strip()
					.codePoints().allMatch(codePoint -> SENTENCE_BOUNDARIES.indexOf(codePoint) >= 0)) {
				return ending.start();
			}
		}
		return -1;
	}

	private boolean isSentenceBoundary(String text, int index, TextProtection protection) {
		return SENTENCE_BOUNDARIES.indexOf(text.charAt(index)) >= 0 && !protection.protectedPositions()[index]
				&& !isTechnicalDot(text, index);
	}

	private boolean isTechnicalDot(String text, int index) {
		if (text.charAt(index) != '.' || index + 1 >= text.length()) {
			return false;
		}
		char next = text.charAt(index + 1);
		return next >= '0' && next <= '9' || index > 0 && isAsciiWord(text.charAt(index - 1)) && isAsciiWord(next);
	}

	private boolean isAsciiWord(char character) {
		return character >= '0' && character <= '9' || character >= 'a' && character <= 'z'
				|| character >= 'A' && character <= 'Z' || character == '_';
	}

	private record PreparedText(String text, int[] offsets) {
	}

	private record RequestRange(int start, int end) {
	}

	private record TextProtection(boolean[] protectedPositions, boolean[] quotedPositions, List<RequestRange> quotedRanges) {
	}
}
