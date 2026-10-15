package com.ubot.chat.service;

import com.ubot.ai.dto.ConversationTurn;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.repository.ConversationTurnRepository;
import com.ubot.chat.repository.QuestionLogRepository;
import com.ubot.common.GlobalException;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.service.LlmService;
import com.ubot.prompt.format.SubQuestionFormat;
import com.ubot.prompt.service.SubQuestionPromptService;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 한 턴에서 검색할 하위 질문 목록을 만드는 유일한 곳입니다. 판정 LLM을 부를지는 여기서만 정합니다.
 * 판정 LLM은 한 번의 호출로 멀티턴 맥락 보완과 질문 분리를 함께 합니다.
 */
@Slf4j
@Service
public class SubQuestionPlanner {
	/** 판정 LLM에 넘기는 이전 대화 수입니다. */
	static final int MAX_HISTORY_TURNS = 3;

	private final boolean enabled;
	private final ConversationTurnRepository conversationTurnRepository;
	private final QuestionLogRepository questionLogRepository;
	private final SubQuestionPromptService subQuestionPromptService;
	private final LlmService llmService;

	public SubQuestionPlanner(
			@Value("${CHAT_SUB_QUESTION_ENABLED:true}") boolean enabled,
			ConversationTurnRepository conversationTurnRepository,
			QuestionLogRepository questionLogRepository,
			SubQuestionPromptService subQuestionPromptService,
			LlmService llmService) {
		this.enabled = enabled;
		this.conversationTurnRepository = conversationTurnRepository;
		this.questionLogRepository = questionLogRepository;
		this.subQuestionPromptService = subQuestionPromptService;
		this.llmService = llmService;
	}

	SubQuestionPlan plan(AnswerAttemptsHistory attempt) {
		if (attempt.isResearch()) {
			return reuseSource(attempt);
		}
		if (!enabled) {
			return SubQuestionPlan.original(attempt.getQuestion(), SubQuestionPlan.Source.ORIGINAL);
		}
		long startedAt = System.nanoTime();
		try {
			List<ConversationTurn> history = attempt.getConversationId() == null
					? List.of()
					: conversationTurnRepository.findPreviousTurns(attempt.getId(), MAX_HISTORY_TURNS);
			LlmResponseDto response = llmService.generateAnswer(
					subQuestionPromptService.createPrompt(attempt.getQuestion(), history));
			List<String> subQuestions = SubQuestionFormat.parse(response.answer());
			// 질문 원문은 남기지 않고 개수와 원문에서 바뀌었는지만 남깁니다.
			log.info("하위 질문을 정리했습니다: 시도ID={}, 이전대화수={}, 하위질문수={}, 다시씀={}, 처리시간={}ms",
					attempt.getId(), history.size(), subQuestions.size(),
					subQuestions.size() > 1 || !subQuestions.getFirst().equals(attempt.getQuestion()),
					elapsedMillis(startedAt));
			return new SubQuestionPlan(subQuestions, SubQuestionPlan.Source.LLM);
		} catch (GlobalException | DataAccessException exception) {
			// 판정은 검색 품질을 높이는 단계라, 실패해도 답변 전체를 실패시키지 않고 원문으로 진행합니다.
			// 작업이 취소된 경우는 다음 단계의 취소 확인에서 멈춥니다.
			log.warn("하위 질문 정리에 실패해 원문으로 검색합니다: 시도ID={}, 사유={}, 처리시간={}ms",
					attempt.getId(), exception.getMessage(), elapsedMillis(startedAt));
			return SubQuestionPlan.original(attempt.getQuestion(), SubQuestionPlan.Source.LLM_FAILED);
		}
	}

	/** 재검색은 원본과 같은 질문을 다른 의도로 다시 찾는 것이므로 원본의 하위 질문을 그대로 씁니다. */
	private SubQuestionPlan reuseSource(AnswerAttemptsHistory attempt) {
		List<String> subQuestions = questionLogRepository
				.findAllByAttemptIdOrderBySubQuestionIndexAsc(attempt.getSourceAttemptId()).stream()
				.map(questionLog -> StringUtils.hasText(questionLog.getSubQuestion())
						? questionLog.getSubQuestion()
						: questionLog.getUserQuestion())
				.toList();
		// 하위 질문을 저장하기 전에 성공한 원본은 질문 로그가 시도와 연결되어 있지 않습니다.
		if (subQuestions.isEmpty()) {
			return SubQuestionPlan.original(attempt.getQuestion(), SubQuestionPlan.Source.ORIGINAL);
		}
		return new SubQuestionPlan(subQuestions, SubQuestionPlan.Source.REUSED);
	}

	private long elapsedMillis(long startedAt) {
		return (System.nanoTime() - startedAt) / 1_000_000;
	}
}
