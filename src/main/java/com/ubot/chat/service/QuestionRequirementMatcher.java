package com.ubot.chat.service;

import com.ubot.chat.service.QuestionMorphology.Morph;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 반려 중복 판정의 보수적인 보조 검사입니다. 기존 Kiwi 분석을 재사용하고 동의어나 생략된 맥락을 추정하지 않습니다.
 * 종결 어미·높임만 제외한 형태소가 순서까지 같아야 합니다. 대상·수치·단위·조사·부정·조건·요구는 그대로 비교합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuestionRequirementMatcher {

	private static final Set<String> CONTEXT_DEPENDENT = Set.of(
			"그", "이", "저", "그것", "그거", "이것", "이거", "저것", "저거", "그곳", "여기", "거기", "저기",
			"오늘", "내일", "어제", "이번", "다음", "지난", "지금", "현재", "아까", "앞서", "이전", "해당", "동일");
	private static final Set<String> CLAUSE_JOINERS = Set.of("고", "며", "면서", "거나", "든지", "지만", "는데", "ㄴ데");
	private static final Set<String> INTERROGATIVES = Set.of("뭐", "무엇", "누구", "어디", "언제", "얼마", "몇");

	private final QuestionMorphology morphology;

	/**
	 * 비어 있으면 동일성을 확인할 근거가 부족합니다. 복합 요청·지시어·분석 실패는 새 후보로 남깁니다.
	 * 숫자와 명사는 원문 표기도 보존하므로 형태소 정규화가 다른 상품명이나 조건을 같게 만들지 않습니다.
	 */
	public Optional<List<String>> signature(String question) {
		if (question == null || question.isBlank()) return Optional.empty();
		List<Morph> morphs;
		try {
			morphs = morphology.analyze(SubQuestionGuard.normalize(question));
		} catch (MorphologyException exception) {
			log.warn("반려 중복의 질문 분석을 확인하지 못해 검토 후보를 유지합니다");
			return Optional.empty();
		}
		int end = morphs.size();
		while (end > 0 && morphs.get(end - 1).tag().equals("SF")) end--;
		while (end > 0 && isPoliteEnding(morphs.get(end - 1))) end--;
		List<String> signature = new ArrayList<>();
		int subjects = 0;
		boolean request = false;
		for (int index = 0; index < end; index++) {
			Morph morph = morphs.get(index);
			String tag = morph.tag();
			if (tag.equals("SF") || tag.equals("SP") || tag.equals("JC") || tag.startsWith("SS")
					|| tag.equals("EF") || tag.equals("UN")
					|| tag.startsWith("W_") || tag.equals("EC") && CLAUSE_JOINERS.contains(morph.form())
					|| tag.equals("NP") && !INTERROGATIVES.contains(morph.form())
					|| (tag.equals("NP") || tag.equals("MM") || tag.equals("NNG") || tag.equals("MAG"))
							&& CONTEXT_DEPENDENT.contains(morph.form())) return Optional.empty();
			if (tag.equals("EP") && morph.form().equals("시")) continue;
			if (tag.equals("JX") && morph.form().equals("요")) continue;
			boolean subject = tag.equals("NNG") || tag.equals("NNP") || tag.equals("SL");
			if (subject) subjects++;
			request |= morph.isPredicate() || tag.equals("VCP") || tag.equals("VCN")
					|| INTERROGATIVES.contains(morph.form());
			String form = subject || tag.equals("SN") || tag.startsWith("S") ? morph.surface() : morph.form();
			signature.add(tag + ":" + form);
		}
		// '그거?', '유심?'처럼 대상·요구를 특정할 수 없는 짧은 입력은 자동 생략하지 않습니다.
		if (subjects == 0 || subjects == 1 && !request) return Optional.empty();
		return Optional.of(List.copyOf(signature));
	}

	private boolean isPoliteEnding(Morph morph) {
		return morph.tag().equals("EF") || morph.tag().equals("JX") && morph.form().equals("요")
				|| morph.tag().equals("EP") && morph.form().equals("시");
	}
}
