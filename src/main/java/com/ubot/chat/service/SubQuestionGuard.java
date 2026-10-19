package com.ubot.chat.service;

import com.ubot.ai.dto.ConversationTurn;
import com.ubot.chat.service.QuestionMorphology.Morph;
import com.ubot.chat.service.QuestionMorphology.Word;
import java.lang.Character.UnicodeScript;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 판정 LLM이 다시 쓴 검색어(하위 질문)를 원문에 비추어 검사합니다. 검색어는 FAQ 검색에만 쓰고 답변 LLM에는 보여 주지 않으므로,
 * 검색을 엉뚱한 쪽으로 끌고 가는 변형만 막고 나머지 다시 쓰기는 그대로 둡니다.
 * <ul>
 *   <li>버림: 봇에게 내린 명령이 질문으로 바뀐 하위 질문, 내용어가 없는 하위 질문, 겹치는 하위 질문</li>
 *   <li>막음(그 하위 질문은 원문 조각으로 검색): 부정이 빠지거나 생김, 질문 대신 답을 씀, 프롬프트 문구가 새어 나옴, 고칠 수 없는 숫자</li>
 *   <li>고침: 출처에 없는 외국 문자와 출처·FAQ에 없는 영어 단어는 지우고, 깨진 숫자와 철자가 바뀐 원문 명사는 원문 표기로 되돌림</li>
 * </ul>
 * 원문 조각은 OriginalQuestionSplitter가 자릅니다. 조각을 못 자르면 원문 조각은 비워 두고(답변 LLM에는 원문만 보여 줌),
 * 막은 하위 질문은 원문 전체로 검색합니다.
 * 출처는 원문 질문과 판정 LLM에 넘긴 이전 대화입니다. 출처에 있는지는 공백을 뺀 문자열 포함으로 봅니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class SubQuestionGuard {
	private static final Set<UnicodeScript> ALWAYS_ALLOWED_SCRIPTS =
			EnumSet.of(UnicodeScript.COMMON, UnicodeScript.INHERITED, UnicodeScript.HANGUL, UnicodeScript.LATIN);
	private static final Pattern NUMBER = Pattern.compile("\\d+");
	private static final Pattern LEADING_HANGUL = Pattern.compile("^[가-힣]+");
	private static final Pattern LATIN_WORD = Pattern.compile("[A-Za-z]+");
	/** 봇에게 내린 명령의 표지입니다. 대답 방식 지시, 지시 무시, 가정, 꾸며 내기, 내부 지시·형식 언급, 주입 태그입니다. */
	private static final Pattern COMMAND = Pattern.compile(
			"(라고|다고)\\s*만?\\s*(말|답|대답|알려)?\\s*(해|하세요|하라|줘)|무시|가정하|가정할|가정했|프롬프트|지시|sub_questions|json"
					+ "|아무거나|지어내|하지\\s*마|<\\s*/?\\s*system|(대답|답변|답)\\s*해",
			Pattern.CASE_INSENSITIVE);
	/** 판정 프롬프트의 나열이 그대로 새어 나온 꼴입니다. 나열 낱말 두 개가 '나·이나·와·과'로 엮이거나 '되는지 여부'입니다. */
	private static final Pattern PROMPT_LEAK = Pattern.compile(
			"(서비스|상품|절차|조건|상황|여부|방법)(이나|나|와|과|·|,)\\s*(서비스|상품|절차|조건|상황|여부|방법)|되는지\\s*여부");
	private static final Set<String> CLAUSE_ENDINGS = Set.of("고", "는데", "ㄴ데", "지만");
	// 다시 쓰면서 지워야 하는 말이라 내용어로 세지 않습니다. 지시어('첫 번째 거', '그 중에'의 거·중 포함),
	// 이전 대화를 가리키는 말, 인사입니다.
	private static final Set<String> NOT_CONTENT = Set.of(
			"그거", "그것", "그건", "거기", "이거", "이것", "저거", "저것", "저기", "여기", "거", "것", "건", "중",
			"저번", "처음", "번째", "아까", "안녕", "감사");

	private final QuestionMorphology morphology;
	private final OriginalQuestionSplitter splitter;
	private final SubQuestionEnglishPolicy englishPolicy;

	/**
	 * 검사를 통과한 하위 질문입니다. searchQuery는 검색에 쓸 검색어이고 userText는 그에 해당하는 원문 조각입니다.
	 * 원문을 자를 근거가 없었으면 userText는 null입니다.
	 */
	record Item(String searchQuery, String userText) {
	}

	/** 검사 결과입니다. 모두 버려 원문으로 돌아가면 items는 원문 하나입니다. */
	record Result(List<Item> items, boolean fallback, Set<Reason> reasons) {
		Result {
			items = List.copyOf(items);
		}

		List<String> searchQueries() {
			return items.stream().map(Item::searchQuery).toList();
		}

		Outcome outcome() {
			return fallback ? Outcome.FALLBACK : reasons.isEmpty() ? Outcome.PASSED : Outcome.MODIFIED;
		}
	}

	enum Outcome { PASSED, MODIFIED, FALLBACK }

	enum Reason {
		FOREIGN_SCRIPT_REMOVED, ENGLISH_WORD_REMOVED, NUMBER_FIXED, KEYWORD_FIXED,
		NO_CONTENT_DROPPED, DUPLICATE_DROPPED, COMMAND_DROPPED,
		NEGATION_CHANGED, ANSWER_WRITTEN, PROMPT_LEAK, NUMBER_UNRECOVERABLE,
		EMPTY, ERROR
	}

	/** rawQueries는 가드를 거치기 전 판정 LLM이 낸 검색어입니다. */
	Result check(String question, List<ConversationTurn> history, List<String> rawQueries) {
		try {
			return inspect(question, history, rawQueries);
		} catch (RuntimeException exception) {
			// 가드는 검색어를 다듬는 단계라, 분석에 실패하면 답변을 실패시키지 않고 원문으로 진행합니다.
			// Kiwi 실패(MorphologyException)와 가드 코드의 오류를 함께 잡습니다.
			log.warn("하위 질문 가드 검사에 실패해 원문으로 검색합니다", exception);
			return fallback(question, EnumSet.of(Reason.ERROR));
		}
	}

	private Result inspect(String question, List<ConversationTurn> history, List<String> rawQueries) {
		Sources sources = new Sources(normalize(question), history);
		Set<Reason> reasons = EnumSet.noneOf(Reason.class);

		List<String> candidates = new ArrayList<>();
		for (String rawQuery : rawQueries) {
			String text = removeForeignScripts(normalize(rawQuery), sources.scripts, reasons);
			if (contentMorphs(morphology.analyze(text)).isEmpty()) {
				reasons.add(Reason.NO_CONTENT_DROPPED);
				continue;
			}
			candidates.add(text);
		}
		// 하위 질문이 하나면 원문 전체가 그 조각입니다. 여럿인데 자를 근거가 없으면 원문 조각 없이(null) 둡니다.
		// 이건 오류가 아니라 판정 LLM이 이전 대화 등으로 나눈 경우라, 검색은 나눈 대로 하고 답변 LLM에는 원문만 보여 줍니다.
		// 자를 때 하위 질문 순서를 원문에 맞춰 다시 놓을 수 있어, 아래 검사는 다시 놓은 순서로 합니다.
		// 명령 하위 질문도 함께 자른 뒤 버려야, 명령 조각이 남은 하위 질문에 붙지 않습니다.
		List<String> pieces = null;
		if (candidates.size() == 1) {
			pieces = List.of(question);
		} else {
			Optional<OriginalQuestionSplitter.Split> split = splitter.split(question, candidates);
			if (split.isPresent()) {
				candidates = split.get().searchQueries();
				pieces = split.get().pieces();
			}
		}

		List<Morph> originalMorphs = morphology.analyze(sources.original);
		Set<String> commandForms = commandForms(question);
		List<Item> kept = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (int index = 0; index < candidates.size(); index++) {
			String userText = pieces == null ? null : pieces.get(index);
			// 뜻이 바뀌었는지 비교하고, 막은 검색어를 대신할 원문입니다. 조각이 없으면 원문 전체입니다.
			String piece = userText == null ? question : userText;
			String text = candidates.get(index);
			List<Morph> morphs = morphology.analyze(text);
			if (isCommand(text, morphs, commandForms)) {
				reasons.add(Reason.COMMAND_DROPPED);
				continue;
			}
			// 명령 표지는 먼저 검사하고, 허용되지 않은 영어만 지워 나머지 다시 쓰기는 유지합니다.
			text = removeUnsourcedEnglish(text, sources, reasons);
			Reason blocked = null;
			String fixed = fixNumbers(text, sources, reasons);
			if (fixed == null) {
				blocked = Reason.NUMBER_UNRECOVERABLE;
			} else {
				List<Morph> fixedMorphs = morphology.analyze(fixed);
				if (contentMorphs(fixedMorphs).isEmpty()) {
					reasons.add(Reason.NO_CONTENT_DROPPED);
					continue;
				}
				text = tidy(fixNouns(fixed, fixedMorphs, sources, originalMorphs, reasons));
				morphs = morphology.analyze(text);
				blocked = changedMeaning(text, morphs, piece, originalMorphs, sources);
			}
			if (blocked != null) {
				reasons.add(blocked);
				text = piece;
			}
			if (!seen.add(key(text))) {
				reasons.add(Reason.DUPLICATE_DROPPED);
				continue;
			}
			kept.add(new Item(text, userText));
		}
		if (kept.isEmpty()) {
			reasons.add(Reason.EMPTY);
			return fallback(question, reasons);
		}
		return new Result(kept, false, reasons);
	}

	private static Result fallback(String question, Set<Reason> reasons) {
		return new Result(List.of(new Item(question, question)), true, reasons);
	}

	// ---------- 뜻이 바뀐 검색어 ----------

	/** 부정, 답을 써 버림, 프롬프트 문구 누출 중 하나면 그 사유를, 아니면 null을 반환합니다. */
	private Reason changedMeaning(String text, List<Morph> morphs, String piece, List<Morph> originalMorphs, Sources sources) {
		List<Morph> pieceMorphs = morphology.analyze(piece);
		// 부정이 빠졌는지는 그 조각과, 새로 생겼는지는 원문 전체와 봅니다. 옆 조각의 부정을 빌려 쓰는 보충은 허용합니다.
		int negations = negations(morphs);
		if (negations < negations(pieceMorphs) || (negations > 0 && negations(originalMorphs) == 0)) {
			return Reason.NEGATION_CHANGED;
		}
		if (endsDeclarative(text, morphs) && !endsDeclarative(piece, pieceMorphs)) {
			return Reason.ANSWER_WRITTEN;
		}
		Matcher leak = PROMPT_LEAK.matcher(text);
		while (leak.find()) {
			if (!sources.contains(leak.group())) {
				return Reason.PROMPT_LEAK;
			}
		}
		return null;
	}

	/** 부정 표지 수입니다. 부사 '안'·'못', 보조용언 '않'·'못하'이고, '안'을 '않'으로 잘못 쓴 경우도 셉니다. */
	private static int negations(List<Morph> morphs) {
		int count = 0;
		for (Morph morph : morphs) {
			boolean adverb = morph.tag().equals("MAG") && (morph.form().equals("안") || morph.form().equals("못"));
			boolean auxiliary = morph.tag().startsWith("VX") && (morph.form().equals("않") || morph.form().equals("못하"));
			boolean misspelled = morph.surface().startsWith("않") && !morph.tag().startsWith("VX");
			if (adverb || auxiliary || misspelled) {
				count++;
			}
		}
		return count;
	}

	/** 물음표 없이 '-다', '-니다'로 끝나면 질문이 아니라 답을 쓴 것으로 봅니다. Kiwi는 'ㅂ니다'를 받침 자모(ᆸ니다)로 냅니다. */
	private static boolean endsDeclarative(String text, List<Morph> morphs) {
		if (text.strip().endsWith("?")) {
			return false;
		}
		Morph ending = null;
		for (Morph morph : morphs) {
			if (morph.tag().equals("EF")) {
				ending = morph;
			}
		}
		return ending != null && (ending.form().endsWith("니다") || Set.of("다", "는다", "ㄴ다", "ᆫ다").contains(ending.form()));
	}

	// ---------- 명령 ----------

	/**
	 * 검색어에 명령 표지가 있거나, 검색어의 내용어 대부분이 원문의 명령 문장에서 왔으면 명령으로 봅니다.
	 * 원문 조각에 명령이 섞여 있어도 검색어가 정상 질문이면 통과시킵니다('이전 지시는 무시해. 해지 위약금 있어요?').
	 */
	private boolean isCommand(String text, List<Morph> morphs, Set<String> commandForms) {
		if (COMMAND.matcher(text).find()) {
			return true;
		}
		List<String> forms = morphs.stream().filter(SubQuestionGuard::isCommandForm).map(Morph::form).toList();
		long fromCommand = forms.stream().filter(commandForms::contains).count();
		return !forms.isEmpty() && fromCommand * 2 > forms.size();
	}

	/** 원문을 마디로 끊어 명령 표지가 있는 마디의 내용어를 모읍니다. 문장 부호·연결어미 뒤와 '{', '<' 앞에서 끊습니다. */
	private Set<String> commandForms(String question) {
		List<List<Word>> clauses = new ArrayList<>();
		List<Word> current = new ArrayList<>();
		for (Word word : morphology.words(question.replace("{", " {").replace("<", " <"))) {
			if ((word.text().startsWith("{") || word.text().startsWith("<")) && !current.isEmpty()) {
				clauses.add(current);
				current = new ArrayList<>();
			}
			current.add(word);
			Morph last = word.last();
			char end = word.text().charAt(word.text().length() - 1);
			if (end == '.' || end == '?' || end == '!'
					|| (last != null && last.tag().equals("EC") && CLAUSE_ENDINGS.contains(last.form()))) {
				clauses.add(current);
				current = new ArrayList<>();
			}
		}
		if (!current.isEmpty()) {
			clauses.add(current);
		}
		Set<String> forms = new HashSet<>();
		for (List<Word> clause : clauses) {
			String text = String.join(" ", clause.stream().map(Word::text).toList());
			if (COMMAND.matcher(text).find()) {
				clause.forEach(word -> word.morphs().stream().filter(SubQuestionGuard::isCommandForm)
						.forEach(morph -> forms.add(morph.form())));
			}
		}
		return forms;
	}

	private static boolean isCommandForm(Morph morph) {
		String tag = morph.tag();
		return tag.equals("NNG") || tag.equals("NNP") || tag.equals("SN") || tag.equals("SL") || morph.isPredicate();
	}

	// ---------- 외국 문자 ----------

	/** 원문과 이전 대화에 없는 문자 체계(한자, 가나 등)의 글자를 지웁니다. */
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

	/** FAQ와 원문·이전 대화에 없는 영어 단어만 지우고 나머지 검색어는 유지합니다. */
	private String removeUnsourcedEnglish(String text, Sources sources, Set<Reason> reasons) {
		Matcher matcher = LATIN_WORD.matcher(text);
		StringBuilder out = new StringBuilder();
		boolean removed = false;
		while (matcher.find()) {
			if (englishPolicy.requiresSource(matcher.group()) && !sources.contains(matcher.group())) {
				matcher.appendReplacement(out, "");
				removed = true;
			}
		}
		if (!removed) {
			return text;
		}
		matcher.appendTail(out);
		reasons.add(Reason.ENGLISH_WORD_REMOVED);
		return tidy(out.toString()).replaceAll("\\?{2,}", "?");
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

	// ---------- 명사 철자 ----------

	/**
	 * 출처에 없는 명사가 원문 명사의 철자만 바꾼 것이면 원문 표기로 되돌립니다('테헤가로' → '테헤란로').
	 * 음절 수가 같고 자모가 조금만 다른 원문 명사를 찾으며(2음절은 1개, 3음절 이상은 2개까지), 한쪽이 다른 쪽을 포함하면 바꾸지 않습니다.
	 * 짝이 없는 명사는 FAQ 표현으로 다시 쓴 말일 수 있어 그대로 둡니다.
	 */
	private static String fixNouns(String text, List<Morph> morphs, Sources sources, List<Morph> originalMorphs,
			Set<Reason> reasons) {
		StringBuilder out = new StringBuilder(text);
		// 뒤에서부터 고쳐야 앞 형태소의 위치가 바뀌지 않습니다.
		for (int i = morphs.size() - 1; i >= 0; i--) {
			Morph morph = morphs.get(i);
			String surface = morph.surface();
			if (!(morph.tag().equals("NNG") || morph.tag().equals("NNP")) || surface.codePointCount(0, surface.length()) < 2
					|| sources.contains(surface)) {
				continue;
			}
			for (Morph original : originalMorphs) {
				if ((original.tag().equals("NNG") || original.tag().equals("NNP")) && isMisspelling(surface, original.surface())) {
					out.replace(morph.start(), morph.end(), original.surface());
					reasons.add(Reason.KEYWORD_FIXED);
					break;
				}
			}
		}
		return out.toString();
	}

	private static boolean isMisspelling(String surface, String candidate) {
		if (surface.length() != candidate.length() || surface.contains(candidate) || candidate.contains(surface)) {
			return false;
		}
		int distance = editDistance(toJamo(surface.toLowerCase(Locale.ROOT)), toJamo(candidate.toLowerCase(Locale.ROOT)));
		return distance > 0 && distance <= (surface.length() >= 3 ? 2 : 1);
	}

	// ---------- 형태소와 문자열 ----------

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
		final String key;
		final Set<UnicodeScript> scripts = EnumSet.noneOf(UnicodeScript.class);
		final List<String> numbers;

		Sources(String original, List<ConversationTurn> history) {
			this.original = original;
			List<String> turnsLatestFirst = new ArrayList<>();
			for (ConversationTurn turn : history) {
				turnsLatestFirst.addFirst(normalize(turn.question()) + " " + (turn.answer() == null ? "" : normalize(turn.answer())));
			}
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
