package com.ubot.chat.job.stream;

import com.ubot.chat.job.dto.ChatRequestContext;
import com.ubot.ranking.enums.RegionSido;
import java.util.LinkedHashMap;
import java.util.Map;

// 작업 ID와 접수 때만 아는 좌표·지역만 담음. 질문·사용자 정보는 Worker가 DB에서 읽음
public record ChatJobMessage(long jobId, ChatRequestContext context) {
	private static final String JOB_ID = "jobId";
	private static final String LATITUDE = "latitude";
	private static final String LONGITUDE = "longitude";
	private static final String REGION = "region";

	Map<String, String> toFields() {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put(JOB_ID, String.valueOf(jobId));
		if (context.latitude() != null) {
			fields.put(LATITUDE, String.valueOf(context.latitude()));
			fields.put(LONGITUDE, String.valueOf(context.longitude()));
		}
		if (context.region() != null) {
			fields.put(REGION, context.region().name());
		}
		return fields;
	}

	// 형식이 틀리면 IllegalArgumentException
	static ChatJobMessage fromFields(Map<?, ?> fields) {
		Object jobId = fields.get(JOB_ID);
		if (jobId == null) {
			throw new IllegalArgumentException("jobId가 없는 메시지입니다: " + fields.keySet());
		}
		return new ChatJobMessage(Long.parseLong(jobId.toString()), new ChatRequestContext(
				toDouble(fields.get(LATITUDE)),
				toDouble(fields.get(LONGITUDE)),
				fields.get(REGION) == null ? null : RegionSido.valueOf(fields.get(REGION).toString())));
	}

	private static Double toDouble(Object value) {
		return value == null ? null : Double.valueOf(value.toString());
	}
}
