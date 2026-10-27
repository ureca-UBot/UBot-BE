package com.ubot.ai.tool.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 선택약정 할인율은 FAQ 문장(규칙 설명)과 요금제 조회 도구의 설정값(약정 할인가 계산) 두 곳에 있습니다.
 * 한쪽만 바뀌면 FAQ 설명과 도구가 계산한 금액이 어긋나므로, 시드 FAQ에서 "선택약정"과 "N%"가 함께 나오는 문장을 모두 찾아
 * N이 설정 기본값과 같은지 확인합니다. FAQ ID 대신 문장 패턴으로 찾아 새로 추가한 FAQ도 검사합니다.
 */
class ContractDiscountConsistencyTest {

    private static final Path SEED = Path.of("tools/faq-db/seed/baseline/baseline-faqs.csv");
    private static final Path APPLICATION = Path.of("src/main/resources/application.yml");
    private static final Pattern DEFAULT_PERCENT = Pattern.compile("PRODUCT_CONTRACT_DISCOUNT_PERCENT:(\\d+)");
    private static final Pattern PERCENT = Pattern.compile("(\\d+)\\s*%");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");

    @Test
    void faqContractDiscountRateMatchesToolSetting() throws IOException {
        Matcher configured = DEFAULT_PERCENT.matcher(Files.readString(APPLICATION, StandardCharsets.UTF_8));
        assertThat(configured.find()).as("application.yml의 선택약정 할인율 기본값").isTrue();
        int percent = Integer.parseInt(configured.group(1));

        List<String> mismatched = new ArrayList<>();
        int checked = 0;
        for (String sentence : SENTENCE_END.split(Files.readString(SEED, StandardCharsets.UTF_8))) {
            if (!sentence.contains("선택약정")) {
                continue;
            }
            Matcher rates = PERCENT.matcher(sentence);
            while (rates.find()) {
                checked++;
                if (Integer.parseInt(rates.group(1)) != percent) {
                    mismatched.add(sentence.strip());
                }
            }
        }

        assertThat(checked).as("선택약정 할인율을 말하는 FAQ 문장 수").isPositive();
        assertThat(mismatched).as("설정 기본값 %d%%와 다른 선택약정 할인율 문장", percent).isEmpty();
    }
}
