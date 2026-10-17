package com.ubot.ai.dto;

import java.util.List;

/**
 * 이전 대화 한 턴입니다. question은 사용자가 입력한 원문입니다.
 * searchQueries는 판정 LLM에 넘기는 그 턴의 검색어로, 답변에 성공한 턴은 그 턴의 하위 질문별 검색어가, 실패한 턴은 원문 하나가 들어 있습니다.
 * 답변 LLM에는 question과 answer를 user/assistant 메시지로 넘깁니다. 실패한 턴의 answer는 null입니다.
 */
public record ConversationTurn(String question, List<String> searchQueries, String answer) {

    public ConversationTurn {
        searchQueries = searchQueries == null ? List.of() : List.copyOf(searchQueries);
    }

    public boolean isAnswered() {
        return answer != null;
    }
}
