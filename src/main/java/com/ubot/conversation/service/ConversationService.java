package com.ubot.conversation.service;

import com.ubot.chat.repository.AnswerAttemptsHistoryRepository;
import com.ubot.chat.repository.QuestionLogRepository;
import com.ubot.common.GlobalException;
import com.ubot.common.PageResponseDto;
import com.ubot.common.exception.CommonErrorCode;
import com.ubot.conversation.dto.response.ConversationMessageResponseDto;
import com.ubot.conversation.dto.response.ConversationResponseDto;
import com.ubot.conversation.entity.Conversation;
import com.ubot.conversation.exception.ConversationErrorCode;
import com.ubot.conversation.exception.ConversationException;
import com.ubot.conversation.repository.ConversationRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConversationService {
	private static final Set<Integer> ALLOWED_PAGE_SIZES = Set.of(10, 20, 50);

	private final ConversationRepository conversationRepository;
	private final AnswerAttemptsHistoryRepository answerAttemptsHistoryRepository;
	private final QuestionLogRepository questionLogRepository;

	@Transactional
	public Long createGuestConversation() {
		return conversationRepository.save(Conversation.createGuest()).getId();
	}

	@Transactional
	public boolean claimGuestConversation(Long conversationId, Long userId) {
		Conversation conversation = conversationRepository.findConversationForLock(conversationId).orElse(null);
		if (conversation == null || conversation.isDeleted() || !conversation.isGuest()) {
			return false;
		}
		conversation.assignToMember(userId);
		conversationRepository.flush();
		answerAttemptsHistoryRepository.assignGuestAttemptsToUser(conversationId, userId);
		questionLogRepository.assignGuestQuestionLogsToUser(conversationId, userId);
		return true;
	}

	@Transactional
	public Conversation getOrCreateMemberConversation(Long userId, UUID conversationId) {
		if (conversationId == null) {
			return conversationRepository.save(Conversation.createMember(userId));
		}
		Conversation conversation = conversationRepository.findMemberConversationForLock(conversationId, userId)
				.filter(found -> !found.isDeleted())
				.orElseThrow(() -> new ConversationException(ConversationErrorCode.CONVERSATION_NOT_FOUND));
		conversation.touch();
		return conversation;
	}

	@Transactional(readOnly = true)
	public UUID getPublicId(Long conversationId) {
		return conversationRepository.findByIdAndDeletedAtIsNull(conversationId).map(Conversation::getPublicId).orElse(null);
	}

	@Transactional
	public void deleteConversation(Long userId, UUID conversationId) {
		Conversation conversation = conversationRepository.findMemberConversationForLock(conversationId, userId)
				.orElseThrow(() -> new ConversationException(ConversationErrorCode.CONVERSATION_NOT_FOUND));
		if (conversation.isDeleted()) {
			return;
		}
		if (answerAttemptsHistoryRepository.existsByConversationIdAndStatus(conversation.getId(), "PENDING")) {
			throw new ConversationException(ConversationErrorCode.CONVERSATION_IN_PROGRESS);
		}
		conversation.delete();
	}

	@Transactional(readOnly = true)
	public PageResponseDto<ConversationResponseDto> getMyConversationList(Long userId, int page, int size) {
		if (!ALLOWED_PAGE_SIZES.contains(size)) {
			throw new GlobalException(CommonErrorCode.INVALID_PARAMETER);
		}
		Page<ConversationResponseDto> conversations = conversationRepository
				.findSummaryListByUserId(userId, PageRequest.of(page, size))
				.map(ConversationResponseDto::from);
		return PageResponseDto.from(conversations);
	}

	@Transactional(readOnly = true)
	public List<ConversationMessageResponseDto> getConversationMessageList(Long userId, UUID conversationId) {
		Conversation conversation = findMemberConversation(userId, conversationId);
		return questionLogRepository.findAllByConversationIdAndSubQuestionIndexOrderByCreatedAtAscIdAsc(
						conversation.getId(), 1).stream()
				.map(ConversationMessageResponseDto::from)
				.toList();
	}

	private Conversation findMemberConversation(Long userId, UUID conversationId) {
		return conversationRepository.findByPublicIdAndUserIdAndDeletedAtIsNull(conversationId, userId)
				.orElseThrow(() -> new ConversationException(ConversationErrorCode.CONVERSATION_NOT_FOUND));
	}
}
