package com.ubot.faq.dto.response;

import java.util.Objects;

import com.ubot.faq.enums.Intent;

/**
 * FAQ 벡터 검색 결과 한 건입니다. 채팅 답변 생성과 intent 분기에 사용합니다.
 *
 * @param faqId FAQ ID. 답변 근거(evidence_ids)와 faq_log 저장에 사용합니다.
 * @param question FAQ 질문 원문
 * @param answer FAQ 답변 원문
 * @param similarityScore 사용자 질문과의 코사인 유사도(1에 가까울수록 유사). FAQ 벡터가 비어 있으면 0.0입니다.
 * @param intent FAQ에 저장된 처리 의도. 채팅이 이 값으로 자료 수집 방식을 정하므로 {@code null}일 수 없습니다.
 */
public record FaqSearchResponseDto(Long faqId, String question, String answer, double similarityScore, Intent intent) {

    /*
     * intent = null 방지
     * 
     * null 을 허용할 시:
     * ChatContextCollector.collect()의 Collectors.groupingBy(FaqSearchResponseDto::intent, ...)
     * → 원인을 찾기 어려운 NPE 발생
     * 
     * 프롬프트에 들어가는 FAQ의 나머지 필드는 PromptService.formatFaqs()에서 검사
     */
    public FaqSearchResponseDto {
    	Objects.requireNonNull(intent, "intent는 필수입니다.");
    }
}
