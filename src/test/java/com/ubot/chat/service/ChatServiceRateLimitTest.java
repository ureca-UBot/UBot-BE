package com.ubot.chat.service;

import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.chat.job.service.ChatSubmissionService;
import com.ubot.chat.util.IpRegionResolver;
import com.ubot.faq.enums.Intent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("ChatService 회원 접수 제한 연결")
class ChatServiceRateLimitTest {
	private static final String KEY = "a".repeat(64);

	private final ChatAttemptsService attempts = mock(ChatAttemptsService.class);
	private final ChatInputGuardService inputGuard = mock(ChatInputGuardService.class);
	private final ChatSubmissionService submission = mock(ChatSubmissionService.class);
	private final ChatRateLimitService rateLimit = mock(ChatRateLimitService.class);
	private final ChatService service = new ChatService(
			attempts, inputGuard, submission, mock(IpRegionResolver.class), rateLimit);

	@Test
	@DisplayName("한도를 넘은 회원의 질문·재시도·재검색은 질문 검사와 시도 생성 전에 거절한다")
	void rejectsMemberRequestsBeforeAnyWork() {
		doThrow(new ChatException(ChatErrorCode.MEMBER_RATE_LIMITED)).when(rateLimit).validateMemberRequest(7L);

		assertRateLimited(() -> service.createChat(7L, 1L, "질문", null, "203.0.113.1"));
		assertRateLimited(() -> service.retryChat(7L, KEY, "203.0.113.1"));
		assertRateLimited(() -> service.researchChat(7L, KEY, Intent.GENERAL, null, "203.0.113.1"));
		verifyNoInteractions(attempts, inputGuard, submission);
	}

	@Test
	@DisplayName("게스트 질문·재시도는 회원 제한을 세지 않는다")
	void guestRequestsAreNotCounted() {
		service.createGuestChat(3L, "질문", null, "203.0.113.1");
		try {
			service.retryGuestChat(3L, KEY, "203.0.113.1");
		} catch (RuntimeException ignored) {
			// 목 시도 기록이 없어 재시도 자체는 실패해도 됩니다. 제한 호출 여부만 봅니다.
		}

		verify(rateLimit, never()).validateMemberRequest(anyLong());
	}

	private void assertRateLimited(Executable request) {
		assertThatThrownBy(request::execute).isInstanceOfSatisfying(ChatException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ChatErrorCode.MEMBER_RATE_LIMITED));
	}
}
