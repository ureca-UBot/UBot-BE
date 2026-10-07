package com.ubot.chat.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.ubot.ai.dto.Location;
import com.ubot.ai.dto.StoreMapResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatStoreDtoTest {
	private final StoreMapResult map = new StoreMapResult(new Location(37.5, 127.0), "강남역", 3.0, List.of());

	@Test
	void 위치_요청도_지도_결과도_없으면_매장과_무관한_답변이라_null이다() {
		assertThat(ChatStoreDto.of(false, null)).isNull();
	}

	@Test
	void 위치가_필요하면_지도_없이_위치_필요만_담는다() {
		assertThat(ChatStoreDto.of(true, null)).isEqualTo(new ChatStoreDto(true, null));
	}

	@Test
	void 조회_결과가_있으면_지도를_담는다() {
		assertThat(ChatStoreDto.of(false, map)).isEqualTo(new ChatStoreDto(false, map));
	}
}
