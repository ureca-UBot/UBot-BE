package com.ubot.chat.config;

import java.nio.file.Files;
import java.nio.file.Path;
import kr.pe.bab2min.Kiwi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 하위 질문 가드가 쓰는 Kiwi 형태소 분석기입니다. 모델은 Gradle downloadKiwi 작업이 받습니다.
 * 모델을 불러오지 못하면 가드 없이 동작하지 않도록 기동을 멈춥니다.
 */
@Configuration
public class KiwiConfig {
	@Bean(destroyMethod = "close")
	public Kiwi kiwi(@Value("${kiwi.model-path}") String modelPath) throws Exception {
		Path path = Path.of(modelPath).toAbsolutePath();
		if (!Files.isDirectory(path)) {
			throw new IllegalStateException("Kiwi 모델 디렉터리가 없습니다: " + path
					+ " (./gradlew downloadKiwi로 받거나 KIWI_MODEL_PATH를 지정하세요)");
		}
		return Kiwi.init(path.toString());
	}
}
