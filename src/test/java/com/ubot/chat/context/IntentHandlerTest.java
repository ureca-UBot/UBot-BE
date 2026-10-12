package com.ubot.chat.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.ContextSection;
import com.ubot.ai.dto.Location;
import com.ubot.ai.dto.StoreMapResult;
import com.ubot.ai.dto.UserDataSummary;
import com.ubot.ai.tool.AiTool;
import com.ubot.ai.tool.NearbyStoreSearcher;
import com.ubot.ai.tool.UserDataSummarizer;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.user.exception.UserErrorCode;
import com.ubot.user.exception.UserException;
import java.time.LocalDate;
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
	void USER_DATA는_미리_조회한_사용자_정보를_넣고_가입_정보_도구를_켠다() {
		UserDataSummarizer summarizer = mock(UserDataSummarizer.class);
		UserDataSummary summary = new UserDataSummary(LocalDate.of(2026, 10, 12), null, List.of());
		when(summarizer.summarize(1L)).thenReturn(summary);
		when(summarizer.format(summary)).thenReturn("조회 기준일: 2026-10-12");
		var materials = AnswerMaterials.builder("질문");

		new UserIntentHandler(summarizer).contribute(context, results, materials);

		AnswerMaterials built = materials.build();
		assertThat(built.sections()).containsExactly(
				new ContextSection(UserIntentHandler.SECTION_TITLE, "조회 기준일: 2026-10-12"));
		assertThat(built.tools()).containsExactly(AiTool.USER_DATA_LOOKUP);
	}

	@Test
	void USER_DATA는_사용자가_없으면_예외_대신_안내_섹션을_넣고_도구를_켜지_않는다() {
		UserDataSummarizer summarizer = mock(UserDataSummarizer.class);
		when(summarizer.summarize(1L)).thenThrow(new UserException(UserErrorCode.USER_NOT_FOUND));
		var materials = AnswerMaterials.builder("질문");

		new UserIntentHandler(summarizer).contribute(context, results, materials);

		AnswerMaterials built = materials.build();
		assertThat(built.sections()).containsExactly(
				new ContextSection(UserIntentHandler.SECTION_TITLE, UserIntentHandler.NOT_FOUND_CONTENT));
		assertThat(built.tools()).isEmpty();
	}

	@Test
	void USER_DATA는_미리_조회가_실패하면_안내_섹션을_넣고_도구로_다시_조회하게_한다() {
		UserDataSummarizer summarizer = mock(UserDataSummarizer.class);
		when(summarizer.summarize(1L)).thenThrow(new IllegalStateException("db down"));
		var materials = AnswerMaterials.builder("질문");

		new UserIntentHandler(summarizer).contribute(context, results, materials);

		AnswerMaterials built = materials.build();
		assertThat(built.sections()).containsExactly(
				new ContextSection(UserIntentHandler.SECTION_TITLE, UserIntentHandler.LOOKUP_FAILED_CONTENT));
		assertThat(built.tools()).containsExactly(AiTool.USER_DATA_LOOKUP);
	}

	@Test
	void USER_DATA는_비회원이면_조회하지_않고_로그인_안내만_넣는다() {
		UserDataSummarizer summarizer = mock(UserDataSummarizer.class);
		var materials = AnswerMaterials.builder("질문");

		new UserIntentHandler(summarizer).contribute(new ChatContext(null, "질문", null), results, materials);

		AnswerMaterials built = materials.build();
		assertThat(built.sections()).extracting(ContextSection::content)
				.containsExactly(UserIntentHandler.LOGIN_REQUIRED_CONTENT);
		assertThat(built.tools()).isEmpty();
		verifyNoInteractions(summarizer);
	}
}
