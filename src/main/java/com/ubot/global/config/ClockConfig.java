package com.ubot.global.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ClockConfig {
	/** "오늘" 기준이 필요한 곳에서 주입받아 씁니다. 서버 시간대와 관계없이 한국 날짜를 기준으로 합니다. */
	@Bean
	public Clock clock() {
		return Clock.system(ZoneId.of("Asia/Seoul"));
	}
}
