package com.ubot.ai.service;

import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.OutputGuardResult;
import com.ubot.ai.dto.OutputGuardResult.Reason;
import com.ubot.ai.dto.StoreMapResult;
import com.ubot.ai.dto.SubQuestionFaqs;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.service.ChatInputGuardService;
import com.ubot.common.GlobalException;
import com.ubot.forbiddenword.exception.ForbiddenWordErrorCode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 기존 입력·출력 검사와 하위 질문을 재사용해 FAQ 보완 검토 후보만 고릅니다. 저장·임베딩·외부 모델 호출은 하지 않습니다. */
@Component
@RequiredArgsConstructor
public class FaqGapCandidateSelector {

    private final ChatInputGuardService chatInputGuardService;
    private final AiOutputGuardService aiOutputGuardService;

    /**
     * 검사 결과와 실제 제공 자료를 받습니다. status나 오류 코드로 후보를 추정하지 않습니다.
     * 답변이 남지 않았거나 형식 오류로 해석이 불가능하면 출력 답변에서 부족한 질문을 복원하지 않습니다.
     * 검색 단계의 미응답은 기존 저장 경로를 사용하므로 이 목록에 중복으로 넣지 않습니다.
     */
    public List<Candidate> select(OutputGuardResult inspection, AnswerMaterials materials, StoreMapResult storeMap) {
        if (inspection == null || materials == null || inspection.rejected()
                || inspection.reasons().contains(Reason.FORMAT_ERROR)) return List.of();
        String preparedQuestion = prepare(materials.question());
        if (preparedQuestion == null) return List.of();

        List<SubQuestionFaqs> questions = materials.subQuestions().isEmpty()
                ? List.of(new SubQuestionFaqs(preparedQuestion, materials.faqs(), preparedQuestion))
                : materials.subQuestions();
        List<Candidate> candidates = new ArrayList<>();
        Set<String> selected = new HashSet<>();
        for (int index = 0; index < questions.size(); index++) {
            SubQuestionFaqs question = questions.get(index);
            if (!question.isAnswered()) continue;
            String searchQuery = prepare(question.searchQuery());
            if (searchQuery == null) continue;

            String userText = null;
            if (StringUtils.hasText(question.userText())) {
                userText = prepare(question.userText());
                // 입력 가드가 제거한 조각이나 다른 턴의 원문을 현재 질문의 원문으로 다시 넣지 않습니다.
                if (userText == null || !normalizeSpacing(preparedQuestion).contains(normalizeSpacing(userText))) continue;
            } else if (questions.size() == 1) {
                userText = preparedQuestion;
            }
            // 여러 하위 질문의 원문 조각이 없으면 null을 유지합니다. 전체 원문이나 새 질문으로 채우지 않습니다.
            SubQuestionFaqs safeQuestion = new SubQuestionFaqs(searchQuery, question.faqs(), userText);
            List<String> missingTopics = aiOutputGuardService.findMissingFaqTopics(
                    safeQuestion, materials, storeMap, inspection);
            if (missingTopics.isEmpty() || !selected.add(searchQuery)) continue;
            candidates.add(new Candidate(index + 1, searchQuery, userText, missingTopics));
        }
        return List.copyOf(candidates);
    }

    private String prepare(String question) {
        if (!StringUtils.hasText(question)) return null;
        try {
            return chatInputGuardService.prepareQuestion(question);
        } catch (GlobalException exception) {
            if (exception.getErrorCode() == ChatErrorCode.PROMPT_INJECTION_DETECTED
                    || exception.getErrorCode() == ChatErrorCode.NO_USABLE_QUESTION
                    || exception.getErrorCode() == ForbiddenWordErrorCode.FORBIDDEN_WORD_DETECTED) return null;
            // 검사 기능의 기술 오류를 FAQ 부족이나 정상적인 제외 결과로 숨기지 않습니다.
            throw exception;
        }
    }

    private String normalizeSpacing(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    /**
     * 질문·순번은 기존 하위 질문 그대로이며 userText는 안전한 원문 조각이 없으면 null입니다.
     * missingTopics는 기존 출력 가드가 읽은 부족한 항목명입니다. 이미 답한 부분을 새 질문으로 다시 만들지 않습니다.
     * 한 하위 질문을 더 잘게 자를 근거가 없으면 원래 맥락과 부족한 항목을 별도로 보존합니다.
     */
    public record Candidate(int subQuestionIndex, String searchQuery, String userText, List<String> missingTopics) {
        public Candidate {
            missingTopics = List.copyOf(missingTopics);
        }
    }
}
