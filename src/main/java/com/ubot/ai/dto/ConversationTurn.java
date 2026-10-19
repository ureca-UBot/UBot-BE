package com.ubot.ai.dto;

/**
 * 이전 대화 한 턴입니다. question은 사용자가 입력한 원문이고, answer는 사용자에게 보여 준 답변입니다. 실패한 턴의 answer는 null입니다.
 * 판정 LLM·가드·멀티턴 판정·답변 LLM 모두 이 두 값만 씁니다. 판정 LLM이 다시 쓴 검색어는 넣지 않습니다.
 * 검색어를 이전 대화로 쓰면 판정 LLM이 한 번 지어낸 말이 다음 턴부터 사용자가 말한 것처럼 굳어지기 때문입니다.
 */
public record ConversationTurn(String question, String answer) {

    public boolean isAnswered() {
        return answer != null;
    }
}
