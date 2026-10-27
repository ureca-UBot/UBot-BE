package com.ubot.ai.dto;

import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 하위 질문 하나와 그 질문으로 찾은 FAQ입니다. 유사도 기준을 넘은 FAQ가 없으면 faqs는 비어 있습니다.
 * searchQuery는 검색에 쓴 검색어(판정 LLM이 다시 쓴 것)이고, userText는 그 하위 질문에 해당하는 사용자 원문 조각입니다.
 * 답변 LLM에는 검색어를 보여 주지 않고 userText만 보여 줍니다(검색어에는 판정 LLM의 의미 변형이 섞일 수 있습니다).
 * 질문을 나누지 않았으면 userText는 사용자가 입력한 원문입니다. 질문을 나눴지만 원문을 자를 근거가 없었으면 null이며,
 * 이때 답변 LLM에는 하위 질문별 제목 없이 원문만 보여 줍니다.
 * lookupTargets는 답변 근거와 별개로 보존하는 조회 종류입니다. 프롬프트에는 넣지 않습니다.
 */
public record SubQuestionFaqs(String searchQuery, List<FaqSearchResponseDto> faqs, String userText,
        Set<LookupFailure.Target> lookupTargets) {

    // 회선이 없어도 이름·나이 같은 프로필은 조회할 수 있으므로 회선 기록 질문에만 회선 없음 사유를 씁니다.
    private static final Pattern SUBSCRIPTION_RECORD = Pattern.compile(
            "회선|요금|사용량|잔여|데이터|통화|문자|청구|납부|미납|약정|할부|부가\\s*서비스|결합|로밍|전화\\s*번호|휴대폰\\s*번호|유심|(?i:e?sim)");

    public SubQuestionFaqs {
        faqs = faqs == null ? List.of() : List.copyOf(faqs);
        Set<LookupFailure.Target> targets = EnumSet.noneOf(LookupFailure.Target.class);
        targets.addAll(lookupTargets == null ? targetsOf(faqs) : lookupTargets);
        if (targets.contains(LookupFailure.Target.USER_DATA) && searchQuery != null
                && SUBSCRIPTION_RECORD.matcher(searchQuery).find()) {
            targets.add(LookupFailure.Target.SUBSCRIPTION_DATA);
        }
        lookupTargets = Set.copyOf(targets);
    }

    public SubQuestionFaqs(String searchQuery, List<FaqSearchResponseDto> faqs, String userText) {
        this(searchQuery, faqs, userText, null);
    }

    /** 프롬프트에서 FAQ를 제외해도 그 질문에 필요한 조회 종류는 보존합니다. */
    public SubQuestionFaqs withFaqs(List<FaqSearchResponseDto> values) {
        return new SubQuestionFaqs(searchQuery, values, userText, lookupTargets);
    }

    public SubQuestionFaqs withLookupTarget(LookupFailure.Target target) {
        Set<LookupFailure.Target> targets = EnumSet.noneOf(LookupFailure.Target.class);
        targets.addAll(lookupTargets);
        targets.add(target);
        return new SubQuestionFaqs(searchQuery, faqs, userText, targets);
    }

    private static Set<LookupFailure.Target> targetsOf(List<FaqSearchResponseDto> faqs) {
        Set<LookupFailure.Target> targets = EnumSet.noneOf(LookupFailure.Target.class);
        if (faqs.stream().anyMatch(faq -> faq.intent() == Intent.USER_DATA)) targets.add(LookupFailure.Target.USER_DATA);
        if (faqs.stream().anyMatch(faq -> faq.intent() == Intent.STORE_DATA)) targets.add(LookupFailure.Target.STORE_DATA);
        return Set.copyOf(targets);
    }

    public boolean isAnswered() {
        return !faqs.isEmpty();
    }
}
