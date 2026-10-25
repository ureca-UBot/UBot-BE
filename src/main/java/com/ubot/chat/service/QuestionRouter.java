package com.ubot.chat.service;

import com.ubot.chat.service.QuestionMorphology.Morph;
import com.ubot.chat.service.QuestionMorphology.Word;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 판정 LLM을 부를지 코드로 정합니다. 질문을 나눠야 할 표지나, 이전 대화를 봐야 할 표지가 있으면 판정 LLM을 부르고,
 * 둘 다 없으면 원문으로 먼저 검색합니다(검색이 실패하면 그때 판정 LLM을 부릅니다. ChatAnswerProcessor 참고).
 * 표지를 놓치면 답이 틀리고, 잘못 잡으면 판정 호출이 한 번 늘 뿐이라 표지는 넓게 잡습니다.
 * 멀티턴 표지는 이전 대화가 있을 때만 봅니다. 이전 대화가 없으면 채울 맥락이 없습니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class QuestionRouter {
	/** 이전 대화를 가리키는 말입니다. 판정 LLM이 다시 쓴 검색어에 남아 있으면 맥락을 못 채운 것으로도 봅니다. */
	static final Set<String> POINTERS = Set.of(
			"그거", "그건", "그걸", "그것", "이거", "이건", "이걸", "저거", "저건", "거기", "여기", "아까", "방금", "위에", "그때", "그중");
	private static final List<String> POINTER_PREFIXES = List.of("그거", "그걸", "그건", "이거", "저거");
	/** 앞 대화에 이어 묻는 첫머리 말입니다. */
	private static final List<String> LEADS = List.of("그럼", "그러면", "그래서", "그리고", "또", "아니", "그건", "그거");
	private static final Pattern ORDINAL = Pattern.compile("(첫|두|세|네)\\s*번째|그\\s*중");
	private static final Pattern CONDITION_CHANGE = Pattern.compile("(으로|로)\\s*(바꿔|바꿀|바꾸면|하면|해서)");
	/** 서술어 없이 '-은요?', '-도요?'처럼 끝나는 생략형입니다. */
	private static final Pattern ELLIPSIS_END = Pattern.compile("(은|는|도|면|이면|이랑|랑)요\\s*[?？.]*\\s*$");
	/** 서술어가 있어도 '-면요?'로 끝나면 앞 질문의 조건만 바꿔 묻는 말입니다. */
	private static final Pattern CONDITIONAL_END = Pattern.compile("면요\\s*[?？.]*\\s*$");
	/** 문장 끝 부호입니다. '1.5GB'처럼 숫자 안의 점은 끊지 않도록 뒤에 공백이나 끝이 올 때만 봅니다. */
	private static final Pattern SENTENCE_END = Pattern.compile("[.?!？。]+(?=\\s|$)");
	private static final Pattern LIST_WORDS = Pattern.compile("그리고|둘\\s*다|각각|,");
	private static final Set<String> JOINERS = Set.of("랑", "이랑", "하고", "와", "과", "및");
	private static final Set<String> CONTENT_TAGS = Set.of("NNG", "NNP", "SL", "SN", "XR");

	private final QuestionMorphology morphology;

	/** 분류 결과입니다. multiTurnMarks는 이전 대화가 없으면 비어 있습니다. */
	record Route(List<String> splitMarks, List<String> multiTurnMarks) {
		Route {
			splitMarks = List.copyOf(splitMarks);
			multiTurnMarks = List.copyOf(multiTurnMarks);
		}

		boolean needsJudge() {
			return !splitMarks.isEmpty() || !multiTurnMarks.isEmpty();
		}

		boolean hasMultiTurnMark() {
			return !multiTurnMarks.isEmpty();
		}

		static Route none() {
			return new Route(List.of(), List.of());
		}
	}

	/**
	 * 형태소 분석(Kiwi)이 실패하면 답변을 실패시키지 않고 표지가 없는 것으로 봅니다. 그러면 원문으로 먼저 검색하고,
	 * 기준에 못 미칠 때 판정 LLM을 부르므로 질문 분리·맥락 보완 기회는 남습니다.
	 */
	Route route(String question, boolean hasHistory) {
		try {
			List<Word> words = morphology.words(question);
			return new Route(splitMarks(question, words), hasHistory ? multiTurnMarks(question, words) : List.of());
		} catch (RuntimeException exception) {
			// Kiwi 실패(MorphologyException)와 분석 코드의 오류를 함께 잡습니다.
			log.warn("경로 분류에 실패해 표지가 없는 것으로 보고 원문으로 먼저 검색합니다", exception);
			return Route.none();
		}
	}

	private List<String> multiTurnMarks(String question, List<Word> words) {
		List<String> marks = new ArrayList<>();
		String stripped = question.strip();
		if (words.stream().anyMatch(word -> POINTERS.contains(word.key())
				|| POINTER_PREFIXES.stream().anyMatch(prefix -> word.key().startsWith(prefix)))) {
			marks.add("지시어");
		}
		if (LEADS.stream().anyMatch(stripped::startsWith)) {
			marks.add("이어 묻기");
		}
		if (ORDINAL.matcher(question).find()) {
			marks.add("순서");
		}
		if (CONDITION_CHANGE.matcher(question).find()) {
			marks.add("조건 바꾸기");
		}
		List<Morph> morphs = words.stream().flatMap(word -> word.morphs().stream()).toList();
		if (ELLIPSIS_END.matcher(stripped).find() && morphs.stream().noneMatch(QuestionRouter::isPredicateLike)) {
			marks.add("서술어 없음");
		}
		if (CONDITIONAL_END.matcher(stripped).find()) {
			marks.add("-면요");
		}
		// 첫 명사에 '도'가 붙으면 앞 대화에 덧붙여 묻는 말입니다(요금도, 카드로도).
		words.stream().filter(word -> word.morphs().stream().anyMatch(m -> CONTENT_TAGS.contains(m.tag())))
				.findFirst()
				.map(Word::last)
				.filter(last -> last.tag().equals("JX") && last.form().equals("도"))
				.ifPresent(last -> marks.add("도"));
		long content = morphs.stream()
				.filter(m -> CONTENT_TAGS.contains(m.tag()) && !POINTERS.contains(m.form()))
				.count();
		// 1인칭 소유격과 대상 명사·서술어가 모두 있으면 대상이 이미 명시된 질문입니다.
		// 길이만으로 이전 대화가 필요하다고 판단하지 않습니다. 지시어·이어 묻기·질문 분리 표지는 그대로 둡니다.
		boolean explicitSelfOwner = words.stream().anyMatch(word ->
				word.morphs().stream().anyMatch(m -> m.tag().equals("NP") && Set.of("나", "저").contains(m.form()))
				&& word.morphs().stream().anyMatch(m -> m.tag().equals("JKG")));
		boolean completeSelfQuery = content == 1 && explicitSelfOwner
				&& morphs.stream().anyMatch(QuestionRouter::isPredicateLike);
		if (content <= 1 && !completeSelfQuery) {
			marks.add("짧음");
		}
		return marks;
	}

	private List<String> splitMarks(String question, List<Word> words) {
		List<String> marks = new ArrayList<>();
		long sentences = SENTENCE_END.splitAsStream(question).filter(sentence -> sentence.strip().length() >= 2).count();
		if (sentences >= 2) {
			marks.add("문장 여럿");
		}
		if (LIST_WORDS.matcher(question).find()) {
			marks.add("나열어");
		}
		for (int i = 0; i < words.size() - 1; i++) {
			Morph last = words.get(i).last();
			if (last == null) {
				continue;
			}
			if (last.tag().equals("JC") || (last.tag().startsWith("J") && JOINERS.contains(last.surface()))) {
				addOnce(marks, "접속조사");
			} else if (last.tag().equals("EC") && Set.of("고", "는데", "ㄴ데").contains(last.form())) {
				// '-고 싶다', '-고 있다'처럼 보조용언이 이어지면 서술어 하나입니다.
				List<Morph> following = words.get(i + 1).morphs();
				boolean auxiliary = last.form().equals("고") && !following.isEmpty()
						&& following.getFirst().tag().startsWith("VX");
				if (!auxiliary) {
					addOnce(marks, "연결어미");
				}
			}
		}
		return marks;
	}

	private static boolean isPredicateLike(Morph morph) {
		String tag = morph.tag();
		return tag.startsWith("VV") || tag.startsWith("VA") || tag.startsWith("VCP") || tag.startsWith("XSV")
				|| tag.startsWith("XSA");
	}

	private static void addOnce(List<String> marks, String mark) {
		if (!marks.contains(mark)) {
			marks.add(mark);
		}
	}
}
