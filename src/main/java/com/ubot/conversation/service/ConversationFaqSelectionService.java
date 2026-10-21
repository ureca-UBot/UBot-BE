package com.ubot.conversation.service;

import com.ubot.conversation.dto.request.FaqSelectionRequestDto;
import com.ubot.conversation.dto.response.FaqSelectionResponseDto;
import com.ubot.conversation.entity.ConversationFaqSelection;
import com.ubot.conversation.exception.ConversationErrorCode;
import com.ubot.conversation.exception.ConversationException;
import com.ubot.conversation.repository.ConversationFaqSelectionRepository;
import com.ubot.conversation.repository.ConversationRepository;
import com.ubot.faq.service.FaqService;
import jakarta.persistence.EntityManager;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class ConversationFaqSelectionService {
	private final ConversationFaqSelectionRepository selections;
	private final ConversationRepository conversations;
	private final ConversationService conversationService;
	private final FaqService faqs;
	private final TransactionTemplate tx;
	private final EntityManager entityManager;

	public FaqSelectionResponseDto save(Long userId, FaqSelectionRequestDto request) {
		try {
			return tx.execute(ignored -> {
				var previous = selections.findById(request.selectionId());
				if (previous.isPresent()) return response(userId, request, previous.get());
				var faq = faqs.getActiveFaq(request.faqId());
				var conversation = conversationService.getOrCreateMemberConversation(userId, request.conversationId());
				var saved = new ConversationFaqSelection(request.selectionId(),
						conversation.getId(), faq.id(), faq.question(), faq.answer());
				entityManager.persist(saved);
				entityManager.flush();
				return response(userId, request, saved);
			});
		} catch (DataIntegrityViolationException | jakarta.persistence.PersistenceException duplicate) {
			// 동시 재시도로 실패한 트랜잭션을 먼저 되돌려 빈 대화를 남기지 않음
			return tx.execute(ignored -> response(userId, request,
					selections.findById(request.selectionId()).orElseThrow(() -> duplicate)));
		}
	}

	private FaqSelectionResponseDto response(Long userId, FaqSelectionRequestDto request, ConversationFaqSelection selection) {
		var conversation = conversations.findByIdAndDeletedAtIsNull(selection.getConversationId())
				.filter(value -> Objects.equals(value.getUserId(), userId))
				.orElseThrow(() -> new ConversationException(ConversationErrorCode.CONVERSATION_NOT_FOUND));
		if (!Objects.equals(selection.getFaqId(), request.faqId())
				|| (request.conversationId() != null && !request.conversationId().equals(conversation.getPublicId()))) {
			throw new com.ubot.common.GlobalException(com.ubot.common.exception.CommonErrorCode.INVALID_PARAMETER);
		}
		return new FaqSelectionResponseDto(selection.getId(), conversation.getPublicId(), selection.getFaqId(),
				selection.getQuestion(), selection.getAnswer());
	}
}
