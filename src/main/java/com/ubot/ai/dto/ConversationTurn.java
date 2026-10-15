package com.ubot.ai.dto;

import java.util.List;

/**
 * 판정 LLM에 넘기는 이전 대화 한 턴입니다.
 * 답변에 성공한 턴은 subQuestions에 정리된 하위 질문이, 실패한 턴은 사용자가 입력한 원문 하나가 들어 있고 answer는 null입니다.
 */
public record ConversationTurn(List<String> subQuestions, String answer) {

    public ConversationTurn {
        subQuestions = subQuestions == null ? List.of() : List.copyOf(subQuestions);
    }

    public boolean isAnswered() {
        return answer != null;
    }
}
