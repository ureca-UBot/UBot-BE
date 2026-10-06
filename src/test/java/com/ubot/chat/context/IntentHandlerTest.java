package com.ubot.chat.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.ContextSection;
import com.ubot.ai.dto.Location;
import com.ubot.ai.dto.StoreMapResult;
import com.ubot.ai.tool.AiTool;
import com.ubot.ai.tool.NearbyStoreSearcher;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.user.dto.response.UserResponseDto;
import com.ubot.user.enums.UserRole;
import com.ubot.user.exception.UserErrorCode;
import com.ubot.user.exception.UserException;
import com.ubot.user.service.UserService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntentHandlerTest {
	private final ChatContext context = new ChatContext(1L, "질문", null);
	private final List<FaqSearchResponseDto> results = List.of(
			new FaqSearchResponseDto(1L, "FAQ 질문", "FAQ 답변", 0.9, Intent.GENERAL));

	@Test
	void GENERAL은_검색된_FAQ를_그대로_넣는다() {
		var materials = AnswerMaterials.builder("질문");

		new GeneralIntentHandler().contribute(context, results, materials);

		AnswerMaterials built = materials.build();
		assertThat(built.faqs()).isEqualTo(results);
		assertThat(built.tools()).isEmpty();
	}

	@Test
	void STORE_DATA는_위치가_없으면_조회하지_않고_매장_도구만_켠다() {
		NearbyStoreSearcher searcher = mock(NearbyStoreSearcher.class);
		var materials = AnswerMaterials.builder("질문");

		new StoreIntentHandler(searcher).contribute(context, results, materials);

		AnswerMaterials built = materials.build();
		assertThat(built.tools()).containsExactly(AiTool.STORE_SEARCH);
		assertThat(built.faqs()).isEmpty();
		assertThat(built.sections()).isEmpty();
		assertThat(built.storeMap()).isNull();
		verifyNoInteractions(searcher);
	}

	@Test
	void STORE_DATA는_내_위치_기준이면_도구_없이_바로_조회한다() {
		NearbyStoreSearcher searcher = mock(NearbyStoreSearcher.class);
		Location myLocation = new Location(37.5, 127.0);
		StoreMapResult result = new StoreMapResult(myLocation, null, 3.0, List.of());
		when(searcher.search(myLocation, null)).thenReturn(result);
		when(searcher.format(result)).thenReturn("반경 3km 안에 매장이 없습니다.");
		var materials = AnswerMaterials.builder("질문");

		new StoreIntentHandler(searcher).contribute(new ChatContext(1L, "질문", myLocation), results, materials);

		AnswerMaterials built = materials.build();
		assertThat(built.tools()).isEmpty();
		assertThat(built.sections()).containsExactly(
				new ContextSection(StoreIntentHandler.SECTION_TITLE, "반경 3km 안에 매장이 없습니다."));
		assertThat(built.storeMap()).isSameAs(result);
	}

	@Test
	void STORE_DATA는_내_위치_조회가_실패하면_예외_대신_안내_섹션을_넣는다() {
		NearbyStoreSearcher searcher = mock(NearbyStoreSearcher.class);
		Location myLocation = new Location(37.5, 127.0);
		when(searcher.search(myLocation, null)).thenThrow(new IllegalStateException("db down"));
		var materials = AnswerMaterials.builder("질문");

		new StoreIntentHandler(searcher).contribute(new ChatContext(1L, "질문", myLocation), results, materials);

		AnswerMaterials built = materials.build();
		assertThat(built.sections()).containsExactly(
				new ContextSection(StoreIntentHandler.SECTION_TITLE, StoreIntentHandler.LOOKUP_FAILED_CONTENT));
		assertThat(built.storeMap()).isNull();
		assertThat(built.tools()).isEmpty();
	}

	@Test
	void USER_DATA는_이메일_없이_사용자_정보_섹션을_넣는다() {
		UserService userService = mock(UserService.class);
		when(userService.getActiveUser(1L)).thenReturn(new UserResponseDto(1L, "user@example.com", "홍길동",
				LocalDate.of(2000, 1, 1), null, "서울 강남구", UserRole.USER, LocalDateTime.of(2026, 3, 2, 10, 0)));
		var materials = AnswerMaterials.builder("질문");

		new UserIntentHandler(userService).contribute(context, results, materials);

		ContextSection section = materials.build().sections().getFirst();
		assertThat(section.title()).isEqualTo(UserIntentHandler.SECTION_TITLE);
		assertThat(section.content())
				.contains("이름: 홍길동", "거주 지역: 서울 강남구", "가입일: 2026-03-02")
				.endsWith(UserIntentHandler.UNAVAILABLE_NOTICE)
				.doesNotContain("user@example.com");
	}

	@Test
	void USER_DATA는_사용자가_없으면_예외_대신_안내_섹션을_넣는다() {
		UserService userService = mock(UserService.class);
		when(userService.getActiveUser(1L)).thenThrow(new UserException(UserErrorCode.USER_NOT_FOUND));
		var materials = AnswerMaterials.builder("질문");

		new UserIntentHandler(userService).contribute(context, results, materials);

		assertThat(materials.build().sections()).containsExactly(
				new ContextSection(UserIntentHandler.SECTION_TITLE, UserIntentHandler.NOT_FOUND_CONTENT));
	}

	@Test
	void USER_DATA는_사용자_ID가_없으면_조회하지_않는다() {
		UserService userService = mock(UserService.class);
		var materials = AnswerMaterials.builder("질문");

		new UserIntentHandler(userService).contribute(new ChatContext(null, "질문", null), results, materials);

		assertThat(materials.build().sections()).extracting(ContextSection::content)
				.containsExactly(UserIntentHandler.NOT_FOUND_CONTENT);
		verifyNoInteractions(userService);
	}
}
