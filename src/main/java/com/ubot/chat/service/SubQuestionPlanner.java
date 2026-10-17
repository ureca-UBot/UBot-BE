package com.ubot.chat.service;

import com.ubot.ai.dto.ConversationTurn;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.entity.QuestionLog;
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
 * <ol>
 *   <li>경로 분류(QuestionRouter): 질문 분리 표지나 멀티턴 표지가 있으면 판정 LLM을 부르고, 없으면 원문으로 먼저 검색합니다.
 *       원문 검색이 실패하면 ChatAnswerProcessor가 judge를 불러 판정합니다. 판정 LLM은 한 턴에 한 번만 부릅니다.</li>
 *   <li>판정 LLM은 이전 대화가 있으면 항상 함께 받아, 맥락 보완과 질문 분리를 한 번에 합니다. 출력(검색어)은 검색에만 씁니다.</li>
 *   <li>가드(SubQuestionGuard)가 검색어의 변형을 막고, 원문 조각(OriginalQuestionSplitter)을 붙입니다. 자를 근거가 없으면 원문 조각은 비웁니다.</li>
 *   <li>멀티턴 판정(MultiTurnDetector)으로 답변 LLM에 이전 대화를 넘길지 정합니다.</li>
 * </ol>
 */
@Slf4j
@Service
public class SubQuestionPlanner {
	/** 판정 LLM과 답변 LLM에 넘기는 이전 대화 수입니다. */
	static final int MAX_HISTORY_TURNS = 3;

	private final boolean enabled;
	private final ConversationTurnRepository conversationTurnRepository;
	private final QuestionLogRepository questionLogRepository;
	private final SubQuestionPromptService subQuestionPromptService;
	private final LlmService llmService;
	private final SubQuestionGuard subQuestionGuard;
	private final QuestionRouter questionRouter;
	private final MultiTurnDetector multiTurnDetector;

	public SubQuestionPlanner(
			@Value("${CHAT_SUB_QUESTION_ENABLED:true}") boolean enabled,
			ConversationTurnRepository conversationTurnRepository,
			QuestionLogRepository questionLogRepository,
			SubQuestionPromptService subQuestionPromptService,
			LlmService llmService,
			SubQuestionGuard subQuestionGuard,
			QuestionRouter questionRouter,
			MultiTurnDetector multiTurnDetector) {
		this.enabled = enabled;
		this.conversationTurnRepository = conversationTurnRepository;
		this.questionLogRepository = questionLogRepository;
		this.subQuestionPromptService = subQuestionPromptService;
		this.llmService = llmService;
		this.subQuestionGuard = subQuestionGuard;
		this.questionRouter = questionRouter;
		this.multiTurnDetector = multiTurnDetector;
	}

	SubQuestionPlan plan(AnswerAttemptsHistory attempt) {
		if (attempt.isResearch()) {
			return reuseSource(attempt);
		}
		if (!enabled) {
			return SubQuestionPlan.original(attempt.getQuestion(), SubQuestionPlan.Source.ORIGINAL);
		}
		List<ConversationTurn> history;
		try {
			history = loadHistory(attempt.getId(), attempt.getConversationId());
		} catch (DataAccessException exception) {
			log.warn("이전 대화를 읽지 못해 원문으로 검색합니다: 시도ID={}, 사유={}", attempt.getId(), exception.getMessage());
			return SubQuestionPlan.original(attempt.getQuestion(), SubQuestionPlan.Source.LLM_FAILED);
		}
		QuestionRouter.Route route = questionRouter.route(attempt.getQuestion(), !history.isEmpty());
		if (!route.needsJudge()) {
			log.info("판정할 표지가 없어 원문으로 먼저 검색합니다: 시도ID={}, 이전대화수={}", attempt.getId(), history.size());
			return SubQuestionPlan.searchFirst(attempt.getQuestion(), history, route);
		}
		return judge(attempt, history, route);
	}

	/** 원문으로 먼저 검색했는데 실패했을 때 판정 LLM을 부릅니다. */
	SubQuestionPlan judge(AnswerAttemptsHistory attempt, SubQuestionPlan searchFirst) {
		return judge(attempt, searchFirst.history(), searchFirst.route());
	}

	private SubQuestionPlan judge(AnswerAttemptsHistory attempt, List<ConversationTurn> history, QuestionRouter.Route route) {
		String question = attempt.getQuestion();
		long startedAt = System.nanoTime();
		try {
			LlmResponseDto response = llmService.generateAnswer(subQuestionPromptService.createPrompt(question, history));
			List<String> rawQueries = SubQuestionFormat.parse(response.answer());
			SubQuestionGuard.Result guarded = subQuestionGuard.check(question, history, rawQueries);
			List<SubQuestionPlan.SubQuestion> items = guarded.items().stream()
					.map(item -> new SubQuestionPlan.SubQuestion(item.searchQuery(), item.userText()))
					.toList();
			boolean multiTurn = multiTurnDetector.isMultiTurn(question, history, guarded.searchQueries(), route);
			// 질문 원문은 남기지 않고 개수, 원문에서 바뀌었는지, 가드 결과, 경로, 멀티턴 여부만 남깁니다.
			log.info("하위 질문을 정리했습니다: 시도ID={}, 이전대화수={}, 하위질문수={}, 다시씀={}, 가드={}, 가드사유={}, "
							+ "분리표지={}, 멀티턴표지={}, 멀티턴={}, 처리시간={}ms",
					attempt.getId(), history.size(), items.size(),
					items.size() > 1 || !items.getFirst().searchQuery().equals(question),
					guarded.outcome(), guarded.reasons(), route.splitMarks(), route.multiTurnMarks(), multiTurn,
					elapsedMillis(startedAt));
			SubQuestionPlan.Source source = guarded.fallback() ? SubQuestionPlan.Source.GUARD_FALLBACK : SubQuestionPlan.Source.LLM;
			return new SubQuestionPlan(items, source, history, multiTurn, route, false);
		} catch (GlobalException exception) {
			// 판정은 검색 품질을 높이는 단계라, 실패해도 답변 전체를 실패시키지 않고 원문으로 진행합니다.
			// 작업이 취소된 경우는 다음 단계의 취소 확인에서 멈춥니다.
			log.warn("하위 질문 정리에 실패해 원문으로 검색합니다: 시도ID={}, 사유={}, 처리시간={}ms",
					attempt.getId(), exception.getMessage(), elapsedMillis(startedAt));
			boolean multiTurn = multiTurnDetector.isMultiTurn(question, history, List.of(question), route);
			return new SubQuestionPlan(List.of(new SubQuestionPlan.SubQuestion(question, question)),
					SubQuestionPlan.Source.LLM_FAILED, history, multiTurn, route, false);
		}
	}

	/**
	 * 재검색은 원본과 같은 질문을 다른 의도로 다시 찾는 것이므로 원본의 하위 질문(검색어와 원문 조각)을 그대로 씁니다.
	 * 멀티턴 여부는 원본과 같은 방법으로 다시 구합니다(판정 LLM은 부르지 않습니다).
	 */
	private SubQuestionPlan reuseSource(AnswerAttemptsHistory attempt) {
		String question = attempt.getQuestion();
		List<QuestionLog> sourceLogs =
				questionLogRepository.findAllByAttemptIdOrderBySubQuestionIndexAsc(attempt.getSourceAttemptId());
		// 하위 질문을 저장하기 전에 성공한 원본은 질문 로그가 시도와 연결되어 있지 않습니다.
		if (sourceLogs.isEmpty()) {
			return SubQuestionPlan.original(question, SubQuestionPlan.Source.ORIGINAL);
		}
		// 질문 로그의 sub_question에는 검색어가, user_text에는 원문 조각이 들어 있습니다.
		// 원본에서 원문을 자를 근거가 없었던 턴은 user_text가 비어 있어 그대로 원문 조각 없이 씁니다.
		List<SubQuestionPlan.SubQuestion> items = sourceLogs.stream()
				.map(questionLog -> new SubQuestionPlan.SubQuestion(
						StringUtils.hasText(questionLog.getSubQuestion())
								? questionLog.getSubQuestion()
								: questionLog.getUserQuestion(),
						sourceLogs.size() == 1 ? question : questionLog.getUserText()))
				.toList();
		List<String> searchQueries = items.stream().map(SubQuestionPlan.SubQuestion::searchQuery).toList();
		List<ConversationTurn> history = List.of();
		boolean multiTurn = false;
		if (enabled && attempt.getConversationId() != null) {
			try {
				history = loadHistory(attempt.getSourceAttemptId(), attempt.getConversationId());
				QuestionRouter.Route route = questionRouter.route(question, !history.isEmpty());
				multiTurn = multiTurnDetector.isMultiTurn(question, history, searchQueries, route);
			} catch (DataAccessException exception) {
				log.warn("재검색의 이전 대화를 읽지 못해 이전 대화 없이 답합니다: 시도ID={}, 사유={}",
						attempt.getId(), exception.getMessage());
			}
		}
		return new SubQuestionPlan(items, SubQuestionPlan.Source.REUSED, history, multiTurn, null, false);
	}

	private List<ConversationTurn> loadHistory(Long attemptId, Long conversationId) {
		return conversationId == null ? List.of() : conversationTurnRepository.findPreviousTurns(attemptId, MAX_HISTORY_TURNS);
	}

	private long elapsedMillis(long startedAt) {
		return (System.nanoTime() - startedAt) / 1_000_000;
	}
}
