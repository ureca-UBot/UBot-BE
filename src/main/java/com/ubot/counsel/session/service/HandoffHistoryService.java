package com.ubot.counsel.session.service;

import com.ubot.conversation.service.ConversationService;
import com.ubot.counsel.session.dto.response.HistoryMessageResponseDto;
import com.ubot.counsel.session.repository.HistoryRepository;
import com.ubot.faq.dto.response.FaqResponseDto;
import com.ubot.faq.exception.FaqErrorCode;
import com.ubot.faq.exception.FaqException;
import com.ubot.faq.service.FaqService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class HandoffHistoryService {
	private final ConversationService conversations;
	private final FaqService faqs;
	private final HistoryRepository history;

	@Transactional(readOnly = true)
	public List<HistoryMessageResponseDto> load(long customerId, UUID conversationId, List<Long> faqIds) {
		var snapshot = new ArrayList<HistoryMessageResponseDto>();
		if (conversationId != null) {
			for (var turn : conversations.getConversationMessageList(customerId, conversationId)) {
				appendTurn(snapshot, turn.question(), turn.answer());
			}
		}
		var resolved = new HashMap<Long, Optional<FaqResponseDto>>();
		for (Long faqId : faqIds) {
			resolved.computeIfAbsent(faqId, this::findAvailableFaq)
					.ifPresent(faq -> appendTurn(snapshot, faq.question(), faq.answer()));
		}
		// 재로그인으로 FAQ ID가 사라져도 서버에서 확인해 저장했던 사본은 보존함
		// 동일 문답을 여러 번 선택한 횟수는 유지하면서 재인계 사본만 중복 제거함
		record Key(String role, String content) {}
		var remaining = new HashMap<Key, Integer>();
		for (var item : snapshot) remaining.merge(new Key(item.role(), item.content()), 1, Integer::sum);
		for (var item : history.previousSaved(conversationId)) {
			var key = new Key(item.role(), item.content());
			int count = remaining.getOrDefault(key, 0);
			if (count > 0) remaining.put(key, count - 1);
			else snapshot.add(item);
		}
		return snapshot;
	}

	private Optional<FaqResponseDto> findAvailableFaq(Long faqId) {
		try {
			return Optional.of(faqs.getActiveFaq(faqId));
		} catch (FaqException exception) {
			if (exception.getErrorCode() != FaqErrorCode.FAQ_NOT_FOUND) throw exception;
			// 삭제되거나 존재하지 않는 FAQ 때문에 상담 연결을 막지 않음
			return Optional.empty();
		}
	}

	private void appendTurn(List<HistoryMessageResponseDto> snapshot, String question, String answer) {
		snapshot.add(new HistoryMessageResponseDto(0, "USER", question, "SAVED"));
		if (answer != null && !answer.isEmpty()) {
			snapshot.add(new HistoryMessageResponseDto(0, "AI", answer, "SAVED"));
		}
	}
}
