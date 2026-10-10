package com.ubot.monitoring.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class FaqMonitoringQueryRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;
}
