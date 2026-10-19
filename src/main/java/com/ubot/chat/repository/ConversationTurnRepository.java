package com.ubot.chat.repository;

import com.ubot.ai.dto.ConversationTurn;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 판정 LLM과 답변 LLM에 넘길 이전 대화를 답변 시도와 질문 로그에서 읽습니다. */
@Repository
@RequiredArgsConstructor
public class ConversationTurnRepository {

	private final JdbcTemplate jdbcTemplate;

	/**
	 * 현재 시도와 같은 대화의 이전 턴을 오래된 순으로 최대 limit개 반환합니다. 대화에 속하지 않은 시도면 빈 목록입니다.
	 * <ul>
	 *   <li>턴은 멱등키 하나입니다. 재시도가 있으면 마지막 시도의 결과를 씁니다.</li>
	 *   <li>현재 질문을 처음 물은 시각보다 앞선 턴만 봅니다. 재시도는 나중에 하더라도 처음 물을 때와 같은 이전 대화를 봅니다.</li>
	 *   <li>의도 재검색은 같은 질문을 다시 찾은 것이라 턴으로 세지 않습니다.</li>
	 *   <li>모든 턴에 시도의 원문을 담고, 답변에 성공한 턴은 질문 로그의 답변을 함께 담습니다.
	 *       질문 로그의 검색어(sub_question)는 읽지 않습니다. 판정 LLM이 지어낸 말이 다음 턴으로 옮겨 가지 않게 하기 위해서입니다.</li>
	 * </ul>
	 */
	public List<ConversationTurn> findPreviousTurns(Long attemptId, int limit) {
		List<TurnRow> rows = jdbcTemplate.query(
				"""
				WITH current_attempt AS (
				    SELECT a.conversation_id,
				           a.idempotency_key,
				           (SELECT MIN(first_try.created_at)
				              FROM answer_attempts_history first_try
				             WHERE first_try.idempotency_key = a.idempotency_key) AS asked_at
				    FROM answer_attempts_history a
				    WHERE a.attempt_id = ?
				),
				turns AS (
				    SELECT DISTINCT ON (a.idempotency_key)
				           a.attempt_id,
				           a.question,
				           a.status,
				           MIN(a.created_at) OVER (PARTITION BY a.idempotency_key) AS asked_at
				    FROM answer_attempts_history a
				    JOIN current_attempt c ON a.conversation_id = c.conversation_id
				    WHERE a.source_attempt_id IS NULL
				      AND a.idempotency_key <> c.idempotency_key
				      AND a.created_at < c.asked_at
				    ORDER BY a.idempotency_key, a.attempt_count DESC
				),
				recent_turns AS (
				    SELECT * FROM turns ORDER BY asked_at DESC, attempt_id DESC LIMIT ?
				)
				SELECT t.attempt_id,
				       t.question,
				       t.status,
				       t.asked_at,
				       q.llm_question AS answer
				FROM recent_turns t
				LEFT JOIN question_log q ON q.attempt_id = t.attempt_id AND t.status = 'SUCCESS'
				ORDER BY t.asked_at, t.attempt_id, q.sub_question_index
				""",
				(rs, rowNum) -> new TurnRow(
						rs.getLong("attempt_id"),
						rs.getString("question"),
						rs.getString("answer")),
				attemptId,
				limit);

		// 질문 로그는 하위 질문마다 한 행이지만 답변은 같은 턴이면 같으므로, 턴마다 첫 행만 씁니다.
		// 성공으로 기록됐지만 질문 로그가 없는 턴(질문 로그가 이미 지워진 경우 등)은 실패한 턴처럼 답변이 null입니다.
		Map<Long, TurnRow> firstRowByTurn = new LinkedHashMap<>();
		rows.forEach(row -> firstRowByTurn.putIfAbsent(row.attemptId(), row));
		List<ConversationTurn> turns = new ArrayList<>();
		firstRowByTurn.values().forEach(row -> turns.add(new ConversationTurn(row.question(), row.answer())));
		return turns;
	}

	private record TurnRow(Long attemptId, String question, String answer) {
	}
}
