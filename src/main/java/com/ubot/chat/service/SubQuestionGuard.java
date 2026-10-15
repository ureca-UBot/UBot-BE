package com.ubot.chat.service;

import com.ubot.ai.dto.ConversationTurn;
import java.lang.Character.UnicodeScript;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.pe.bab2min.Kiwi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 판정 LLM이 정리한 하위 질문을 원문 질문과 이전 대화에 비추어 검사합니다.
 * <ul>
 *   <li>뜻을 바꾸지 않고 고칠 수 있는 것(외국 문자, 깨진 숫자, 원문 표기와 다른 키워드, 출처 없는 명사)은 고칩니다.</li>
 *   <li>뜻이 바뀌었을 수 있으면(원문의 의문사가 다른 의문사로 바뀜, 원문 키워드 누락, 고칠 수 없는 숫자) 원문으로 돌아가게 합니다.</li>
 *   <li>이전 질문에서만 가져왔거나 내용어가 없는 하위 질문, 겹치는 하위 질문은 버립니다.</li>
 * </ul>
 * 출처는 원문 질문과 판정 LLM에 넘긴 이전 대화(하위 질문과 답변)입니다. 출처에 있는지는 공백을 뺀 문자열 포함으로 봅니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class SubQuestionGuard {
	private static final Kiwi.AnalyzeOption ANALYZE_OPTION = new Kiwi.AnalyzeOption(Kiwi.Match.allWithNormalizing);
	private static final Set<UnicodeScript> ALWAYS_ALLOWED_SCRIPTS =
			EnumSet.of(UnicodeScript.COMMON, UnicodeScript.INHERITED, UnicodeScript.HANGUL, UnicodeScript.LATIN);
	private static final Pattern NUMBER = Pattern.compile("\\d+");
	private static final Pattern LEADING_HANGUL = Pattern.compile("^[가-힣]+");
	private static final Set<String> INTERROGATIVES =
			Set.of("얼마", "어떻", "어떤", "어디", "언제", "몇", "무엇", "뭐", "왜", "며칠", "누구", "무슨", "어느");
	// 다시 쓰면서 지워야 하는 말이라 원문의 내용어로 세지 않습니다. 지시어('첫 번째 거', '그 중에'의 거·중 포함),
	// 이전 대화를 가리키는 말, 인사입니다.
	private static final Set<String> NOT_CONTENT = Set.of(
			"그거", "그것", "그건", "거기", "이거", "이것", "저거", "저것", "저기", "여기", "거", "것", "건", "중",
			"저번", "처음", "번째", "아까", "안녕", "감사");
	// 어느 질문에나 붙는 용언이라 원문에서 왔다는 근거로 쓰지 않습니다.
	private static final Set<String> LIGHT_PREDICATES = Set.of("있", "없", "하", "되", "이", "아니", "같");

	private final Kiwi kiwi;

	/** 검사 결과입니다. 원문으로 돌아가야 하면 subQuestions는 원문 하나입니다. */
	record Result(List<String> subQuestions, boolean fallback, Set<Reason> reasons) {
		Outcome outcome() {
			return fallback ? Outcome.FALLBACK : reasons.isEmpty() ? Outcome.PASSED : Outcome.MODIFIED;
		}
	}

	enum Outcome { PASSED, MODIFIED, FALLBACK }

	enum Reason {
		FOREIGN_SCRIPT_REMOVED, NUMBER_FIXED, KEYWORD_FIXED, NOUN_REMOVED,
		HISTORY_ONLY_DROPPED, NO_CONTENT_DROPPED, DUPLICATE_DROPPED,
		NUMBER_UNRECOVERABLE, NEW_INTERROGATIVE, KEYWORD_MISSING, EMPTY, ERROR
	}

	Result check(String question, List<ConversationTurn> history, List<String> subQuestions) {
		try {
			return inspect(question, history, subQuestions);
		} catch (RuntimeException exception) {
			// 가드는 검색어를 다듬는 단계라, 분석에 실패하면 답변을 실패시키지 않고 원문으로 진행합니다.
			log.warn("하위 질문 가드 검사에 실패해 원문으로 검색합니다: 사유={}", exception.toString());
			return fallback(question, Reason.ERROR);
		}
	}

	private Result inspect(String question, List<ConversationTurn> history, List<String> subQuestions) {
		Sources sources = new Sources(normalize(question), history);
		List<Morph> originalMorphs = analyze(sources.original);
		Set<String> originalInterrogatives = new HashSet<>();
		originalMorphs.stream().filter(SubQuestionGuard::isInterrogative).forEach(m -> originalInterrogatives.add(m.form()));
		List<Anchor> originalOnly = originalOnlyAnchors(originalMorphs, sources);

		Set<Reason> reasons = EnumSet.noneOf(Reason.class);
		List<String> kept = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (String subQuestion : subQuestions) {
			String text = removeForeignScripts(normalize(subQuestion), sources.scripts, reasons);
			List<Morph> initial = analyze(text);
			if (contentMorphs(initial).isEmpty()) {
				reasons.add(Reason.NO_CONTENT_DROPPED);
				continue;
			}
			if (!history.isEmpty() && !originalOnly.isEmpty() && !mentionsAny(text, initial, originalOnly)) {
				reasons.add(Reason.HISTORY_ONLY_DROPPED);
				continue;
			}
			text = fixNumbers(text, sources, reasons);
			if (text == null) {
				return fallback(question, Reason.NUMBER_UNRECOVERABLE);
			}
			List<Morph> morphs = analyze(text);
			// 원문에 의문사가 없으면 '알려줘'를 '뭐예요?'로 바꾸듯 묻는 꼴만 바꾼 것이라 보고, 의문사가 다른 의문사로 바뀐 경우만 봅니다.
			if (!originalInterrogatives.isEmpty()
					&& morphs.stream().anyMatch(m -> isInterrogative(m) && !originalInterrogatives.contains(m.form()))) {
				return fallback(question, Reason.NEW_INTERROGATIVE);
			}
			text = tidy(fixNouns(text, morphs, sources, originalMorphs, reasons));
			if (contentMorphs(analyze(text)).isEmpty()) {
				reasons.add(Reason.NO_CONTENT_DROPPED);
				continue;
			}
			if (!seen.add(key(text))) {
				reasons.add(Reason.DUPLICATE_DROPPED);
				continue;
			}
			kept.add(text);
		}
		if (kept.isEmpty()) {
			return fallback(question, Reason.EMPTY);
		}
		if (missesOriginalKeyword(originalMorphs, kept)) {
			return fallback(question, Reason.KEYWORD_MISSING);
		}
		return new Result(List.copyOf(kept), false, reasons);
	}

	private static Result fallback(String question, Reason reason) {
		return new Result(List.of(question), true, EnumSet.of(reason));
	}

	// ---------- 외국 문자 ----------

	/** 원문과 이전 대화에 없는 문자 체계(한자, 가나 등)의 글자를 지웁니다. 라틴 문자는 새 명사 검사에서 봅니다. */
	private static String removeForeignScripts(String text, Set<UnicodeScript> allowed, Set<Reason> reasons) {
		StringBuilder out = new StringBuilder();
		text.codePoints().forEach(codePoint -> {
			UnicodeScript script = UnicodeScript.of(codePoint);
			if (ALWAYS_ALLOWED_SCRIPTS.contains(script) || allowed.contains(script)) {
				out.appendCodePoint(codePoint);
			}
		});
		if (out.length() == text.length()) {
			return text;
		}
		reasons.add(Reason.FOREIGN_SCRIPT_REMOVED);
		return tidy(out.toString());
	}

	// ---------- 숫자 ----------

	/**
	 * 출처에 없는 숫자를 가까운 출처 숫자(접두어가 같거나 한 글자만 다름)로 바꿉니다. 원문 숫자, 최근 턴의 숫자 순으로 고릅니다.
	 * 바꾼 숫자 바로 뒤의 한글이 출처에서 그 숫자 뒤에 오지 않으면 깨진 글자로 보고 지웁니다. 고칠 수 없으면 null입니다.
	 */
	private static String fixNumbers(String text, Sources sources, Set<Reason> reasons) {
		Matcher matcher = NUMBER.matcher(text);
		StringBuilder out = new StringBuilder();
		int last = 0;
		while (matcher.find()) {
			if (matcher.start() < last) {
				continue;
			}
			String number = matcher.group();
			out.append(text, last, matcher.start());
			last = matcher.end();
			if (sources.numbers.contains(number)) {
				out.append(number);
				continue;
			}
			String replacement = sources.numbers.stream().filter(candidate -> isCloseNumber(number, candidate))
					.findFirst().orElse(null);
			if (replacement == null) {
				return null;
			}
			out.append(replacement);
			reasons.add(Reason.NUMBER_FIXED);
			Matcher trailing = LEADING_HANGUL.matcher(text.substring(last));
			if (trailing.find() && !sources.contains(replacement + trailing.group())) {
				last += trailing.end();
			}
		}
		return out.append(text.substring(last)).toString();
	}

	private static boolean isCloseNumber(String number, String candidate) {
		return candidate.startsWith(number) || number.startsWith(candidate) || editDistance(toInts(number), toInts(candidate)) <= 1;
	}

	// ---------- 새 명사와 표기 ----------

	/**
	 * 출처에 없는 명사를 고칩니다. 원문이나 이전 대화의 명사와 표기만 조금 다르면 그 표기로 바꾸고, 아니면 뒤에 붙은 조사와 함께 지웁니다.
	 * 관형형 어미 뒤의 의존명사('할 수', '보낼 때')와 '하다'가 붙어 서술어가 된 명사('설정할', '가능한')는 문장의 뼈대라 건드리지 않습니다.
	 */
	private String fixNouns(String text, List<Morph> morphs, Sources sources, List<Morph> originalMorphs,
			Set<Reason> reasons) {
		StringBuilder out = new StringBuilder(text);
		// 뒤에서부터 고쳐야 앞 형태소의 위치가 바뀌지 않습니다.
		for (int i = morphs.size() - 1; i >= 0; i--) {
			Morph morph = morphs.get(i);
			if (!isCheckedNoun(morphs, i) || sources.contains(morph.surface())) {
				continue;
			}
			String similar = similarKeyword(morph.surface(), originalMorphs, sources);
			if (similar != null) {
				out.replace(morph.start(), morph.end(), similar);
				reasons.add(Reason.KEYWORD_FIXED);
				continue;
			}
			int end = morph.end();
			for (int j = i + 1; j < morphs.size() && morphs.get(j).tag().startsWith("J") && morphs.get(j).start() <= end; j++) {
				end = Math.max(end, morphs.get(j).end());
			}
			out.delete(morph.start(), end);
			reasons.add(Reason.NOUN_REMOVED);
		}
		return out.toString();
	}

	private static boolean isCheckedNoun(List<Morph> morphs, int index) {
		Morph morph = morphs.get(index);
		String tag = morph.tag();
		if (!(tag.equals("NNG") || tag.equals("NNP") || tag.equals("NNB") || tag.equals("SL")) || morph.surface().isBlank()) {
			return false;
		}
		if (tag.equals("NNB") && index > 0 && morphs.get(index - 1).tag().startsWith("ETM")) {
			return false;
		}
		return index + 1 >= morphs.size() || !(morphs.get(index + 1).tag().startsWith("XSV")
				|| morphs.get(index + 1).tag().startsWith("XSA"));
	}

	/** 표기만 조금 다른 출처의 명사를 찾습니다. 원문의 명사를 먼저, 그다음 최근 턴부터 이전 대화의 명사를 봅니다. */
	private String similarKeyword(String surface, List<Morph> originalMorphs, Sources sources) {
		if (surface.codePointCount(0, surface.length()) < 2) {
			return null;
		}
		for (Morph morph : originalMorphs) {
			if (isNounLike(morph) && isSimilar(surface, morph.surface())) {
				return morph.surface();
			}
		}
		for (String turn : sources.turnsLatestFirst) {
			for (Morph morph : analyze(turn)) {
				if (isNounLike(morph) && isSimilar(surface, morph.surface())) {
					return morph.surface();
				}
			}
		}
		return null;
	}

	private static boolean isNounLike(Morph morph) {
		return morph.tag().equals("NNG") || morph.tag().equals("NNP") || morph.tag().equals("SL");
	}

	/** 한글은 자모 단위로, 그 밖의 글자는 대소문자를 무시하고 글자 단위로 편집 거리를 잽니다. 긴 낱말만 두 글자까지 봐줍니다. */
	private static boolean isSimilar(String surface, String candidate) {
		if (surface.equals(candidate) || Math.abs(surface.length() - candidate.length()) > 1) {
			return false;
		}
		List<Integer> left = toJamo(surface.toLowerCase(Locale.ROOT));
		List<Integer> right = toJamo(candidate.toLowerCase(Locale.ROOT));
		int distance = editDistance(left, right);
		return distance > 0 && distance <= (Math.min(left.size(), right.size()) >= 10 ? 2 : 1);
	}

	// ---------- 버리기와 누락 ----------

	/**
	 * 원문에만 있고 이전 질문에는 없는 내용어입니다. 하위 질문이 이 중 하나도 담지 않으면 이전 질문을 옮겨 온 것으로 봅니다.
	 * 이전 질문이 원문의 내용어를 모두 담고 있으면(같은 질문을 다시 물으면) 빈 목록이라 이 검사를 하지 않습니다.
	 */
	private static List<Anchor> originalOnlyAnchors(List<Morph> originalMorphs, Sources sources) {
		List<Anchor> anchors = new ArrayList<>();
		for (int i = 0; i < originalMorphs.size(); i++) {
			Morph morph = originalMorphs.get(i);
			boolean predicate = (morph.tag().startsWith("VV") || morph.tag().startsWith("VA"))
					&& !LIGHT_PREDICATES.contains(morph.form());
			if ((isContent(originalMorphs, i) || isInterrogative(morph) || predicate)
					&& !sources.previousQuestionsKey.contains(key(morph.surface()))) {
				anchors.add(new Anchor(key(morph.surface()), morph.form()));
			}
		}
		return anchors;
	}

	/** 원문의 내용어나 의문사가 남은 하위 질문 어디에도 없으면 true입니다. */
	private boolean missesOriginalKeyword(List<Morph> originalMorphs, List<String> subQuestions) {
		String joined = String.join(" ", subQuestions);
		List<Morph> morphs = analyze(joined);
		for (int i = 0; i < originalMorphs.size(); i++) {
			Morph morph = originalMorphs.get(i);
			if ((isContent(originalMorphs, i) || isInterrogative(morph))
					&& !mentionsAny(joined, morphs, List.of(new Anchor(key(morph.surface()), morph.form())))) {
				return true;
			}
		}
		return false;
	}

	private static boolean mentionsAny(String text, List<Morph> morphs, List<Anchor> anchors) {
		String textKey = key(text);
		Set<String> forms = new HashSet<>();
		morphs.forEach(m -> forms.add(m.form()));
		return anchors.stream().anyMatch(anchor -> textKey.contains(anchor.key()) || forms.contains(anchor.form()));
	}

	private static List<Morph> contentMorphs(List<Morph> morphs) {
		List<Morph> content = new ArrayList<>();
		for (int i = 0; i < morphs.size(); i++) {
			if (isContent(morphs, i)) {
				content.add(morphs.get(i));
			}
		}
		return content;
	}

	/** 명사·외국어·숫자·어근이고 지시어나 인사가 아니면 내용어입니다. 관형형 어미 뒤의 의존명사는 문법 요소라 뺍니다. */
	private static boolean isContent(List<Morph> morphs, int index) {
		Morph morph = morphs.get(index);
		String tag = morph.tag();
		boolean content = tag.equals("NNG") || tag.equals("NNP") || tag.equals("SL") || tag.equals("SN") || tag.equals("XR")
				|| (tag.equals("NNB") && (index == 0 || !morphs.get(index - 1).tag().startsWith("ETM")));
		return content && !morph.surface().isBlank() && !NOT_CONTENT.contains(key(morph.surface()));
	}

	private static boolean isInterrogative(Morph morph) {
		return INTERROGATIVES.contains(morph.form());
	}

	// ---------- 형태소와 문자열 ----------

	/** 형태소 분석 결과입니다. form은 Kiwi가 정규화한 형태라 원문과 다를 수 있어, 원문에서 잘라 낸 surface를 함께 둡니다. */
	private record Morph(String form, String tag, int start, int end, String surface) {
	}

	private record Anchor(String key, String form) {
	}

	private List<Morph> analyze(String text) {
		List<Morph> morphs = new ArrayList<>();
		for (Kiwi.Token token : kiwi.tokenize(text, ANALYZE_OPTION)) {
			int start = Math.min(token.position, text.length());
			int end = Math.min(start + token.length, text.length());
			morphs.add(new Morph(token.form, Kiwi.POSTag.toString(token.tag), start, end, text.substring(start, end)));
		}
		return morphs;
	}

	/** NFC로 맞추고 전각 영숫자·기호를 반각으로 바꿉니다. */
	static String normalize(String text) {
		StringBuilder out = new StringBuilder();
		Normalizer.normalize(text, Normalizer.Form.NFC).codePoints().forEach(codePoint -> {
			if (codePoint >= 0xFF01 && codePoint <= 0xFF5E) {
				out.appendCodePoint(codePoint - 0xFEE0);
			} else if (codePoint == 0x3000) {
				out.append(' ');
			} else {
				out.appendCodePoint(codePoint);
			}
		});
		return out.toString();
	}

	/** 출처 비교에 쓰는 형태입니다. 공백을 지우고 영문은 소문자로 맞춥니다. */
	private static String key(String text) {
		return text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
	}

	/** 글자를 지우거나 바꾼 뒤 남은 공백과 문장 부호를 정리합니다. */
	private static String tidy(String text) {
		return text.replace("()", "")
				.replaceAll("\\s+", " ")
				.replaceAll("\\s+([?.!,)])", "$1")
				.replaceAll("\\(\\s+", "(")
				.replaceAll("^[\\s,.]+", "")
				.strip();
	}

	private static List<Integer> toInts(String text) {
		return text.codePoints().boxed().toList();
	}

	/** 한글 음절을 초성·중성·종성으로 나눕니다. 한글이 아닌 글자는 그대로 둡니다. */
	private static List<Integer> toJamo(String text) {
		List<Integer> jamo = new ArrayList<>();
		text.codePoints().forEach(codePoint -> {
			if (codePoint >= 0xAC00 && codePoint <= 0xD7A3) {
				int index = codePoint - 0xAC00;
				jamo.add(0x1100 + index / 588);
				jamo.add(0x1161 + index % 588 / 28);
				if (index % 28 != 0) {
					jamo.add(0x11A7 + index % 28);
				}
			} else {
				jamo.add(codePoint);
			}
		});
		return jamo;
	}

	private static int editDistance(List<Integer> left, List<Integer> right) {
		int[] previous = new int[right.size() + 1];
		int[] current = new int[right.size() + 1];
		for (int j = 0; j <= right.size(); j++) {
			previous[j] = j;
		}
		for (int i = 1; i <= left.size(); i++) {
			current[0] = i;
			for (int j = 1; j <= right.size(); j++) {
				int substitution = previous[j - 1] + (left.get(i - 1).equals(right.get(j - 1)) ? 0 : 1);
				current[j] = Math.min(substitution, Math.min(previous[j], current[j - 1]) + 1);
			}
			int[] swap = previous;
			previous = current;
			current = swap;
		}
		return previous[right.size()];
	}

	/** 한 번의 검사에서 쓰는 출처입니다. */
	private static final class Sources {
		final String original;
		final List<String> turnsLatestFirst = new ArrayList<>();
		final String key;
		final String previousQuestionsKey;
		final Set<UnicodeScript> scripts = EnumSet.noneOf(UnicodeScript.class);
		final List<String> numbers;

		Sources(String original, List<ConversationTurn> history) {
			this.original = original;
			StringBuilder previousQuestions = new StringBuilder();
			for (ConversationTurn turn : history) {
				String questions = normalize(String.join(" ", turn.subQuestions()));
				previousQuestions.append(questions).append(' ');
				turnsLatestFirst.addFirst(questions + " " + (turn.answer() == null ? "" : normalize(turn.answer())));
			}
			this.previousQuestionsKey = SubQuestionGuard.key(previousQuestions.toString());
			StringBuilder all = new StringBuilder(original);
			turnsLatestFirst.forEach(turn -> all.append(' ').append(turn));
			this.key = SubQuestionGuard.key(all.toString());
			all.codePoints().forEach(codePoint -> scripts.add(UnicodeScript.of(codePoint)));
			Set<String> ordered = new LinkedHashSet<>(numbersIn(original));
			turnsLatestFirst.forEach(turn -> ordered.addAll(numbersIn(turn)));
			this.numbers = List.copyOf(ordered);
		}

		boolean contains(String text) {
			return key.contains(SubQuestionGuard.key(text));
		}

		private static List<String> numbersIn(String text) {
			return NUMBER.matcher(text).results().map(match -> match.group()).toList();
		}
	}
}
