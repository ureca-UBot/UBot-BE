package com.ubot.guardreview.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.guardreview.controller.AdminReviewController;
import com.ubot.guardreview.repository.ReviewRepository;
import com.ubot.notification.repository.NotificationRepository;
import com.ubot.notification.service.NotificationService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class ReviewControllerTest {
	ReviewService service = mock(ReviewService.class);
	ReviewRepository repository = mock(ReviewRepository.class);
	ReviewCheckService checks = mock(ReviewCheckService.class);
	AdminReviewController controller = new AdminReviewController(service, repository,
			mock(NotificationRepository.class), mock(NotificationService.class), checks);
	CustomUserDetails user(String role) {
		CustomUserDetails user = mock(CustomUserDetails.class);
		doReturn(List.of(new SimpleGrantedAuthority("ROLE_" + role))).when(user).getAuthorities();
		when(user.getUserId()).thenReturn(99L);
		return user;
	}
	@Test
	void 일반사용자는_분석_검사_승인_기록조회에_접근할수없다() {
		var user = user("USER");
		assertThatThrownBy(() -> controller.getOverview(user))
				.isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
		assertThatThrownBy(() -> controller.startAnalysis(user))
				.isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
		assertThatThrownBy(() -> controller.getFindings(user, 1, null, null))
				.isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
		assertThatThrownBy(() -> controller.approveCandidates(user,
				new com.ubot.guardreview.dto.request.ReviewApprovalRequestDto(1, List.of())))
				.isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
		assertThatThrownBy(
				() -> controller.getCandidatePage(user, com.ubot.guardreview.model.ReviewModels.Kind.WORD, 0, 20, null, null))
				.isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
		verifyNoInteractions(service, repository);
	}
	@Test
	void 관리자는_기존_성공응답형식으로_분석을_접수한다() {
		when(service.startAnalysis(99L)).thenReturn(7L);
		var result = controller.startAnalysis(user("ADMIN"));
		assertThat(result.success()).isTrue();
		assertThat(result.data()).isEqualTo(7L);
	}
	@Test
	void 검사기록과_원문근거는_관리자만_볼수있다() {
		assertThatThrownBy(() -> controller.getChecks(user("USER"), null, null, 0, 20))
				.isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
		assertThatThrownBy(() -> controller.getCheck(user("USER"), 1))
				.isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
		verifyNoInteractions(checks);
	}
	@Test
	void 출처와_판정필터를_검사기록과_후보에_같이_전달한다() {
		var origin = com.ubot.guardreview.model.ReviewCheckModels.Origin.OUTPUT_LLM;
		var outcome = com.ubot.guardreview.model.ReviewCheckModels.Outcome.REJECTED;
		controller.getChecks(user("ADMIN"), origin, outcome, 0, 20);
		controller.getFindings(user("ADMIN"), 5, origin, outcome);
		verify(checks).page(origin, outcome, 0, 20);
		verify(repository).findings(5, origin, outcome);
	}
	@Test
	void 보류_분석접수는_관리자만_선택ID와_출처를_전달한다() {
		var body = new com.ubot.guardreview.dto.request.ReviewReassessmentRequestDto(
				com.ubot.guardreview.model.ReviewCheckModels.Origin.INPUT_LLM, List.of(1L));
		assertThatThrownBy(() -> controller.startReassessment(user("USER"), body))
				.isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
		verifyNoInteractions(service);
		when(service.startReassessment(body.origin(), body.checkIds(), 99L)).thenReturn(7L);
		assertThat(controller.startReassessment(user("ADMIN"), body).data()).isEqualTo(7);
	}
}
