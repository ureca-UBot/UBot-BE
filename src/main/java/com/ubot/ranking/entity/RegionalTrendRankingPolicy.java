package com.ubot.ranking.entity;

import com.ubot.ranking.enums.RegionSido;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
public class RegionalTrendRankingPolicy {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(name = "region_name")
	private RegionSido regionName;

	@Column(name = "min_current_search_count")
	private int minCurrentSearchCount;

	@Column(name = "min_baseline_sample_count")
	private int minBaselineSampleCount;

	@Column(name = "trend_ratio_threshold")
	private double trendRatioThreshold;
}
