package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ubot.ai.dto.AiAnswer;
import com.ubot.ai.dto.SubQuestionFaqs;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.ContextSection;
import com.ubot.ai.dto.Location;
import com.ubot.ai.dto.StoreMapResult;
import com.ubot.ai.service.AiService;
import com.ubot.ai.tool.AiTool;
import com.ubot.ai.tool.NearbyStoreSearcher;
import com.ubot.chat.dto.response.ChatStoreDto;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.job.dto.response.ChatJobStatusResponseDto;
import com.ubot.chat.util.IpRegionResolver;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.retrieval.FaqRetrievalService;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import com.ubot.ranking.enums.RegionSido;
import com.ubot.unanswered.service.UnansweredQuestionService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("채팅 intent 분기 연결 테스트")
class ChatServiceIntentRoutingTest {
	private final FaqVectorService vector = ChatTestFixtures.vectorService();
	private final AiService ai = mock(AiService.class);
	private final ChatAttemptsService attempts = mock(ChatAttemptsService.class);
	private final IpRegionResolver ipRegionResolver = mock(IpRegionResolver.class);
	private final NearbyStoreSearcher nearbyStoreSearcher = mock(NearbyStoreSearcher.class);
	private final AnswerAttemptsHistory attempt = AnswerAttemptsHistory.builder()
			.id(1L).userId(1L).question("근처 매장 알려줘").idempotencyKey("a".repeat(64))
			.attemptCount(1).status("PENDING").build();
	private final List<FaqSearchResponseDto> sources = List.of(
			new FaqSearchResponseDto(1L, "매장 찾기", "근처 매장을 안내합니다.", 0.9, Intent.STORE_DATA),
			new FaqSearchResponseDto(2L, "영업시간", "매장마다 다릅니다.", 0.8, Intent.GENERAL),
			new FaqSearchResponseDto(3L, "낮은 점수", "제외됩니다.", 0.5, Intent.GENERAL));
	private final AnswerAttemptsHistory guestAttempt = AnswerAttemptsHistory.builder()
			.id(2L).conversationId(7L).question("근처 매장 알려줘").idempotencyKey("b".repeat(64))
			.attemptCount(1).status("PENDING").build();
	private final Location myLocation = new Location(37.5, 127.0);
	private final StoreMapResult myLocationStores = new StoreMapResult(myLocation, null, 3.0, List.of());
	private ChatJobTestHarness harness;
	private ChatService service;

	@BeforeEach
	void setUp() {
		ChatAnswerProcessor processor = new ChatAnswerProcessor(FaqRetrievalService.denseOnly(vector), ai, mock(UnansweredQuestionService.class), ChatTestFixtures.collector(nearbyStoreSearcher), ChatTestFixtures.originalSubQuestions(), ChatTestFixtures.disabledCache(), ChatTestFixtures.passthroughSentenceFilter(), ChatTestFixtures.inputGuard());
		ReflectionTestUtils.setField(processor, "topK", 3);
		ReflectionTestUtils.setField(processor, "confidenceThreshold", 0.75);
		harness = new ChatJobTestHarness(attempts, processor).knowAttempts(attempt, guestAttempt);
		service = new ChatService(attempts, ChatTestFixtures.inputGuard(),
				harness.submissionService, ipRegionResolver);
		when(attempts.createAnswerAttempt(1L, null, "근처 매장 알려줘")).thenReturn(attempt);
		when(attempts.createRetryAttempt(1L, attempt.getIdempotencyKey())).thenReturn(attempt);
		when(vector.getSimilarList("근처 매장 알려줘", 3)).thenReturn(sources);
		when(nearbyStoreSearcher.search(myLocation, null)).thenReturn(myLocationStores);
		when(nearbyStoreSearcher.format(myLocationStores)).thenReturn("반경 3km 안에 매장이 없습니다.");
		when(ai.generateAnswer(any(AnswerMaterials.class))).thenReturn(new AiAnswer("답변", null, false));
		when(attempts.createGuestAnswerAttempt(7L, "근처 매장 알려줘")).thenReturn(guestAttempt);
		when(ipRegionResolver.resolve("127.0.0.1")).thenReturn(Optional.of(RegionSido.SEOUL));
		when(attempts.recordSuccess(any(), any(), any(), any())).thenAnswer(call -> {
			AnswerAttemptsHistory saved = call.getArgument(0);
			saved.succeed();
			return harness.logAnswer(call.getArgument(1));
		});
	}

	@Test
	@DisplayName("위치 없이 물으면 threshold를 넘은 FAQ의 intent별로 자료를 모으고 매장 조회 도구를 붙인다")
	void collectsMaterialsByIntentWithStoreTool() {
		service.createChat(1L, null, "근처 매장 알려줘", null, "127.0.0.1");
		harness.runQueuedJobs();

		AnswerMaterials materials = captureMaterials();
		assertThat(materials.question()).isEqualTo("근처 매장 알려줘");
		assertThat(materials.faqs()).extracting(FaqSearchResponseDto::faqId).containsExactly(2L);
		assertThat(materials.tools()).containsExactly(AiTool.STORE_SEARCH);
		assertThat(materials.storeMap()).isNull();
		verifyNoInteractions(nearbyStoreSearcher);
		// faq_log는 intent와 관계없이 threshold를 넘은 검색 결과 전체로 남깁니다.
		// 지역은 접수 때 IP로 정한 값임
		verify(attempts).recordSuccess(attempt, "답변",
				List.of(new SubQuestionFaqs("근처 매장 알려줘", sources.subList(0, 2), "근처 매장 알려줘")), RegionSido.SEOUL);
	}

	@Test
	@DisplayName("내 위치 기준 재요청이면 도구 없이 좌표로 바로 조회한 매장을 자료에 담는다")
	void searchesStoresDirectlyWithMyLocation() {
		service.createChat(1L, null, "근처 매장 알려줘", myLocation, null);
		harness.runQueuedJobs();

		AnswerMaterials materials = captureMaterials();
		assertThat(materials.tools()).isEmpty();
		assertThat(materials.sections()).extracting(ContextSection::content)
				.containsExactly("반경 3km 안에 매장이 없습니다.");
		assertThat(materials.storeMap()).isSameAs(myLocationStores);
		assertThat(materials.faqs()).extracting(FaqSearchResponseDto::faqId).containsExactly(2L);
	}

	@Test
	@DisplayName("비회원도 내 위치 기준 재요청이면 바로 조회한 매장을 자료에 담는다")
	void guestSearchesStoresDirectlyWithMyLocation() {
		service.createGuestChat(7L, "근처 매장 알려줘", myLocation, null);
		harness.runQueuedJobs();

		AnswerMaterials materials = captureMaterials();
		assertThat(materials.tools()).isEmpty();
		assertThat(materials.storeMap()).isSameAs(myLocationStores);
		verify(attempts).recordSuccess(eq(guestAttempt), eq("답변"), any(), isNull());
	}

	@Test
	@DisplayName("조회한 매장 지도 정보를 성공 결과의 store에 담는다")
	void returnsStoreMapInResponse() {
		var storeMap = new StoreMapResult(new Location(37.5, 127.0), "강남역", 3.0, List.of());
		when(ai.generateAnswer(any(AnswerMaterials.class))).thenReturn(new AiAnswer("답변", storeMap, false));

		ChatJobStatusResponseDto response = completeChat();

		assertThat(response.answer()).isEqualTo("답변");
		assertThat(response.store()).isEqualTo(new ChatStoreDto(false, storeMap));
	}

	@Test
	@DisplayName("기준 위치를 정하지 못했으면 store에 위치 필요만 담는다")
	void returnsLocationRequiredInResponse() {
		when(ai.generateAnswer(any(AnswerMaterials.class))).thenReturn(new AiAnswer("위치를 알려 주세요.", null, true));

		ChatJobStatusResponseDto response = completeChat();

		assertThat(response.store()).isEqualTo(new ChatStoreDto(true, null));
	}

	@Test
	@DisplayName("매장을 조회하지 않은 답변에는 store를 담지 않는다")
	void omitsStoreWhenNoStoreResult() {
		ChatJobStatusResponseDto response = completeChat();

		assertThat(response.answer()).isEqualTo("답변");
		assertThat(response.store()).isNull();
	}

	private ChatJobStatusResponseDto completeChat() {
		var submission = service.createChat(1L, null, "근처 매장 알려줘", null, null);
		harness.runQueuedJobs();
		return harness.memberStatus(submission, 1L);
	}

	@Test
	@DisplayName("재시도는 위치 없이 도구 경로로 처리한다")
	void retryHasNoLocation() {
		when(attempts.getLatestAnswerAttempt(1L, attempt.getIdempotencyKey())).thenReturn(attempt);
		service.retryChat(1L, attempt.getIdempotencyKey(), null);
		harness.runQueuedJobs();

		assertThat(captureMaterials().tools()).containsExactly(AiTool.STORE_SEARCH);
		verifyNoInteractions(nearbyStoreSearcher);
	}

	private AnswerMaterials captureMaterials() {
		ArgumentCaptor<AnswerMaterials> materials = ArgumentCaptor.forClass(AnswerMaterials.class);
		verify(ai).generateAnswer(materials.capture());
		return materials.getValue();
	}
}
