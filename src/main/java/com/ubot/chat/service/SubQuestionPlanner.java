package com.ubot.chat.service;

import com.ubot.ai.dto.ConversationTurn;
import com.ubot.ai.service.AiInputGuardService;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.repository.ConversationTurnRepository;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** 기존 경로 분류·검색어 보호·멀티턴 판정을 유지하며 입력 안전 검사와 질문 정리를 연결합니다.
 * 기존 분류기가 필요한 시점에만 안전 판정과 질문 정리를 한 번에 수행합니다. 별도 보조 검사는 없습니다.
 */
@Slf4j
@Service
public class SubQuestionPlanner {
	/** 판정 LLM과 답변 LLM에 넘기는 이전 대화 수입니다. */
	static final int MAX_HISTORY_TURNS = 3;

	private final boolean enabled;
	private final ConversationTurnRepository conversationTurnRepository;
	private final AiInputGuardService aiInputGuardService;
	private final SubQuestionGuard subQuestionGuard;
	private final QuestionRouter questionRouter;
	private final MultiTurnDetector multiTurnDetector;
	private final ChatInputGuardService chatInputGuardService;

	public SubQuestionPlanner(
			@Value("${CHAT_SUB_QUESTION_ENABLED:true}") boolean enabled,
			ConversationTurnRepository conversationTurnRepository,
			AiInputGuardService aiInputGuardService,
			SubQuestionGuard subQuestionGuard,
			QuestionRouter questionRouter,
			MultiTurnDetector multiTurnDetector,
			ChatInputGuardService chatInputGuardService) {
		this.enabled = enabled;
		this.conversationTurnRepository = conversationTurnRepository;
		this.aiInputGuardService = aiInputGuardService;
		this.subQuestionGuard = subQuestionGuard;
		this.questionRouter = questionRouter;
		this.multiTurnDetector = multiTurnDetector;
		this.chatInputGuardService = chatInputGuardService;
	}

	SubQuestionPlan plan(AnswerAttemptsHistory attempt) {
		return plan(attempt, attempt.getQuestion());
	}

	/** 기록 원문과 분리된 입력 가드 정리본으로 질문을 계획합니다. */
	SubQuestionPlan plan(AnswerAttemptsHistory attempt, String question) {
		if (!enabled) {
			// 끄면 원문 하나로 검색합니다. 원문 먼저 검색 경로를 타야 FAQ 시맨틱 캐시도 보고, 기준 미달이어도 판정하지 않습니다(judge).
			return SubQuestionPlan.searchFirst(question, List.of(), QuestionRouter.Route.none());
		}
		List<ConversationTurn> history;
		try {
			// 의도 재검색은 원본과 같은 질문을 다시 묻는 것이라 원본을 물을 때의 이전 대화를 씁니다.
			// 재검색 시도 기준으로 읽으면 원본 질문이 직전 대화로 들어갑니다.
			Long historyAttemptId = attempt.isResearch() ? attempt.getSourceAttemptId() : attempt.getId();
			history = loadHistory(historyAttemptId, attempt.getConversationId());
		} catch (DataAccessException exception) {
			log.warn("입력 안전 검사에 필요한 대화 기록을 읽지 못했습니다: 시도ID={}", attempt.getId());
			throw new ChatException(ChatErrorCode.STORAGE_UNAVAILABLE);
		}
		QuestionRouter.Route route = questionRouter.route(question, !history.isEmpty());
		if (!route.needsJudge()) {
			log.info("판정할 표지가 없어 원문으로 먼저 검색합니다: 시도ID={}, 이전대화수={}", attempt.getId(), history.size());
			return SubQuestionPlan.searchFirst(question, history, route);
		}
		return judge(attempt, question, history, route);
	}

	/** 원문으로 먼저 검색했는데 실패했을 때 판정 LLM을 부릅니다. 질문 정리를 껐으면 부르지 않고 원문 그대로 둡니다. */
	SubQuestionPlan judge(AnswerAttemptsHistory attempt, SubQuestionPlan searchFirst) {
		return judge(attempt, searchFirst, attempt.getQuestion());
	}

	SubQuestionPlan judge(AnswerAttemptsHistory attempt, SubQuestionPlan searchFirst, String question) {
		if (!enabled) {
			return SubQuestionPlan.original(question, SubQuestionPlan.Source.ORIGINAL);
		}
		return judge(attempt, question, searchFirst.history(), searchFirst.route());
	}

	private SubQuestionPlan judge(AnswerAttemptsHistory attempt, String question, List<ConversationTurn> history, QuestionRouter.Route route) {
		long startedAt = System.nanoTime();
		// 활성 안전 판정의 BLOCK·REVIEW·실패는 전파합니다. 안전 판정 OFF의 분류기 실패만 기존대로 복구합니다.
		var inspection = aiInputGuardService.inspectForPlanning(attempt.getId(), attempt.getQuestion(), question, history);
		if (inspection.planningFailed()) {
			boolean multiTurn = multiTurnDetector.isMultiTurn(question, history, List.of(question), route);
			return new SubQuestionPlan(List.of(new SubQuestionPlan.SubQuestion(question, question)),
					SubQuestionPlan.Source.LLM_FAILED, history, multiTurn, route, false);
		}
		List<String> rawQueries = inspection.subQuestions();
		SubQuestionGuard.Result guarded = subQuestionGuard.check(question, history, rawQueries);
		List<SubQuestionPlan.SubQuestion> items = guarded.items().stream()
				.map(item -> new SubQuestionPlan.SubQuestion(item.searchQuery(), item.userText())).toList();
		boolean multiTurn = multiTurnDetector.isMultiTurn(question, history, guarded.searchQueries(), route);
		log.info("하위 질문을 정리했습니다: 시도ID={}, 이전대화수={}, 하위질문수={}, 가드={}, 가드사유={}, "
				+ "분리표지={}, 멀티턴표지={}, 멀티턴={}, 처리시간={}ms", attempt.getId(), history.size(), items.size(),
				guarded.outcome(), guarded.reasons(), route.splitMarks(), route.multiTurnMarks(), multiTurn,
				elapsedMillis(startedAt));
		SubQuestionPlan.Source source = guarded.fallback() ? SubQuestionPlan.Source.GUARD_FALLBACK : SubQuestionPlan.Source.LLM;
		return new SubQuestionPlan(items, source, history, multiTurn, route, false);
	}

	private List<ConversationTurn> loadHistory(Long attemptId, Long conversationId) {
		return conversationId == null ? List.of() : chatInputGuardService.prepareHistory(
				conversationTurnRepository.findPreviousTurns(attemptId, MAX_HISTORY_TURNS));
	}

	private long elapsedMillis(long startedAt) {
		return (System.nanoTime() - startedAt) / 1_000_000;
	}
}
