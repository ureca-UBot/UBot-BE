package com.ubot.ranking.controller;

import com.ubot.common.ApiResponse;
import com.ubot.ranking.dto.response.RankingResponseDto;
import com.ubot.ranking.service.RankingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/rankings")
public class RankingController {
	private final RankingService rankingService;

	//RankingResponseDto 내부의 각 RankingSnapshot들이
	// null -> Redis 집계가 아직 안됨
	// 비어있음 -> Redis에서 집계가 이뤄졌지만 정보가 없음
	// 둘다 프론트에서 집계 결과가 없습니다. 라고 표현해야 함
	@GetMapping
	public ApiResponse<RankingResponseDto> getRankings(){
		return ApiResponse.success(rankingService.getRanking());
	}

}
