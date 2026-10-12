package com.ubot.ai.tool;

import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.ai.config.AiRuntimeProperties;
import com.ubot.ai.dto.AiAnswer;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.service.AiService;
import com.ubot.billing.service.BillingService;
import com.ubot.chat.context.ChatContext;
import com.ubot.chat.context.ChatContextCollector;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.llm.service.LlmService;
import com.ubot.subscription.dto.response.SubscriptionResponseDto;
import com.ubot.subscription.service.SubscriptionService;
import com.ubot.usage.service.UsageService;
import java.io.IOException;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.stubbing.Answer;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * USER_DATA 질문 100개를 실제 LLM으로 답하게 하고, 기대 처리·도구대로 처리됐는지 문서로 남깁니다.
 * FAQ 검색과 intent 분기는 빼고 USER_DATA로 고정한 뒤, 수집기부터는 운영 코드 그대로 실행합니다.
 * 가입 데이터는 테스트 컨테이너 DB에만 넣으므로 실제 DB는 건드리지 않습니다.
 *
 * <p>실행: {@code RUN_ANSWER_EVAL=true ./gradlew test --tests 'com.ubot.ai.tool.UserDataAnswerEvaluation'}
 * LLM 설정은 OS 환경변수, 없으면 루트 .env에서 읽습니다. 보고서는 build/reports/answer-eval/(또는 EVAL_REPORT_DIR)에 남습니다.
 */
@SpringBootTest
@Import({PgvectorTestConfiguration.class, UserDataAnswerEvaluation.FixedClockConfiguration.class})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_ANSWER_EVAL", matches = "true")
class UserDataAnswerEvaluation {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    // 질문의 "지난달", "9월"과 시드 데이터가 실행 날짜와 상관없이 맞도록 기준일을 고정합니다.
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 10, 12, 10, 0);
    private static final String QUESTIONS = "evaluation/user_data_pipeline_questions.csv";
    private static final String SEED = "evaluation/user_data_seed.sql";
    // CSV의 사용자 열 값입니다. 시드 이메일은 eval-a@ubot.test, eval-b@ubot.test입니다.
    private static final List<String> USERS = List.of("A", "B");
    private static final int MAX_CONSECUTIVE_ERRORS = 3;
    private static final int RECORD_MONTHS = 12;
    // USER_DATA 핸들러를 고르는 용도입니다. 핸들러는 FAQ를 자료에 넣지 않습니다.
    private static final FaqSearchResponseDto USER_DATA_FAQ =
            new FaqSearchResponseDto(0L, "평가용 USER_DATA FAQ", "평가용", 1.0, Intent.USER_DATA);

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock evaluationClock() {
            return Clock.fixed(BASE_TIME.atZone(SEOUL).toInstant(), SEOUL);
        }
    }

    @DynamicPropertySource
    static void llmProperties(DynamicPropertyRegistry registry) {
        Map<String, String> env = loadEnv();
        // DB·JWT 같은 다른 키는 넘기지 않습니다. DB는 테스트 컨테이너를 씁니다.
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("AI_MODE", "app.ai.mode");
        keys.put("AI_CHAT_ENGINE", "app.ai.chat-engine");
        keys.put("AI_EMBEDDING_ENGINE", "app.ai.embedding-engine");
        keys.put("OLLAMA_BASE_URL", "ollama.base-url");
        for (String key : List.of("OLLAMA_CHAT_MODEL", "LLM_BASE_URL", "LLM_MODEL", "LLM_CONNECT_TIMEOUT", "LLM_READ_TIMEOUT")) {
            keys.put(key, key);
        }
        keys.forEach((envKey, property) -> {
            String value = env.get(envKey);
            if (value != null && !value.isBlank()) {
                registry.add(property, () -> value);
            }
        });
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ChatContextCollector collector;

    @Autowired
    private AiService aiService;

    @Autowired
    private LlmService llmService;

    @Autowired
    private AiRuntimeProperties aiRuntimeProperties;

    @Autowired
    private UserDataSummarizer summarizer;

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private UsageService usageService;

    @Autowired
    private BillingService billingService;

    @MockitoSpyBean
    private UserDataTools userDataTools;

    private final List<ToolCall> calls = new CopyOnWriteArrayList<>();

    @BeforeEach
    void recordToolCalls() {
        doAnswer(recording()).when(userDataTools).getUsageByMonth(any(), any(), any());
        doAnswer(recording()).when(userDataTools).getBillingByMonth(any(), any(), any());
        doAnswer(recording()).when(userDataTools).getAddons(any(), any());
        doAnswer(recording()).when(userDataTools).getBundles(any(), any());
        doAnswer(recording()).when(userDataTools).getRoamings(any(), any());
    }

    @Test
    void USER_DATA_질문에_실제_LLM으로_답하고_결과를_문서로_남긴다() throws IOException {
        new ResourceDatabasePopulator(new ClassPathResource(SEED)).execute(jdbc.getDataSource());
        Map<String, Long> userIds = new LinkedHashMap<>();
        for (String user : USERS) {
            userIds.put(user, jdbc.queryForObject("SELECT user_id FROM users WHERE email = ? AND deleted_at IS NULL",
                    Long.class, "eval-" + user.toLowerCase() + "@ubot.test"));
        }

        List<Question> questions = readQuestions();
        List<Result> results = new ArrayList<>();
        String abortReason = null;
        int consecutiveErrors = 0;
        for (Question question : questions) {
            Result result = ask(question, question.user().isEmpty() ? null : userIds.get(question.user()));
            results.add(result);
            System.out.printf("[%d/%d] %s %s%n", results.size(), questions.size(),
                    result.matched() ? "일치" : "불일치", question.text());
            consecutiveErrors = result.error() == null ? 0 : consecutiveErrors + 1;
            if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                abortReason = "LLM 호출이 " + MAX_CONSECUTIVE_ERRORS + "번 연속 실패해 중단했습니다. 마지막 오류: " + result.error();
                break;
            }
        }

        Map<String, String> groundTruth = new LinkedHashMap<>();
        userIds.forEach((key, id) -> groundTruth.put(key, describeUser(id)));
        calls.clear();

        Path reportDir = Path.of(firstNonBlank(loadEnv().get("EVAL_REPORT_DIR"), "build/reports/answer-eval"));
        Files.createDirectories(reportDir);
        String fileName = "user-data-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path markdown = reportDir.resolve(fileName + ".md");
        Path csv = reportDir.resolve(fileName + ".csv");
        Files.writeString(markdown, markdown(results, questions.size(), groundTruth, userIds, abortReason), StandardCharsets.UTF_8);
        // 엑셀에서 한글이 깨지지 않게 BOM을 붙입니다.
        Files.writeString(csv, "﻿" + csv(results), StandardCharsets.UTF_8);
        System.out.println("평가 보고서: " + markdown.toAbsolutePath());
        System.out.println("평가 CSV: " + csv.toAbsolutePath());

        if (abortReason != null) {
            fail(abortReason + " 보고서: " + markdown.toAbsolutePath());
        }
    }

    // ---- 실행과 판정 ----

    private Result ask(Question question, Long userId) {
        calls.clear();
        long start = System.nanoTime();
        List<String> enabledTools = List.of();
        String answer = null;
        String error = null;
        try {
            AnswerMaterials materials = collector.collect(new ChatContext(userId, question.text(), null), List.of(USER_DATA_FAQ));
            enabledTools = materials.tools().stream().map(Enum::name).toList();
            AiAnswer aiAnswer = aiService.generateAnswer(materials);
            answer = aiAnswer == null ? null : aiAnswer.answer();
        } catch (RuntimeException exception) {
            error = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        List<ToolCall> questionCalls = List.copyOf(calls);
        return new Result(question, enabledTools, questionCalls, answer, error, elapsedMillis,
                judge(question, enabledTools, questionCalls, error));
    }

    private boolean judge(Question question, List<String> enabledTools, List<ToolCall> questionCalls, String error) {
        if (error != null) {
            return false;
        }
        return switch (question.expected()) {
            // 적힌 도구를 모두, 적힌 횟수 이상 호출해야 일치입니다. 그 밖의 도구를 더 불러도 불일치로 보지 않습니다.
            case "TOOL" -> expectedToolCounts(question.expectedTool()).entrySet().stream()
                    .allMatch(expected -> questionCalls.stream()
                            .filter(call -> call.name().equals(expected.getKey())).count() >= expected.getValue());
            case "LOGIN_REQUIRED" -> questionCalls.isEmpty() && enabledTools.isEmpty();
            default -> questionCalls.isEmpty();
        };
    }

    /** "getAddons;getBundles"는 각 1번, "getUsageByMonth*2"는 2번 호출을 뜻합니다. */
    static Map<String, Integer> expectedToolCounts(String expectedTool) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String token : expectedTool.split(";")) {
            String tool = token.strip();
            if (tool.isEmpty()) {
                continue;
            }
            int times = 1;
            int star = tool.indexOf('*');
            if (star > 0) {
                times = Integer.parseInt(tool.substring(star + 1).strip());
                tool = tool.substring(0, star).strip();
            }
            counts.merge(tool, times, Integer::sum);
        }
        if (counts.isEmpty()) {
            throw new IllegalArgumentException("TOOL 질문에는 기대 도구가 필요합니다.");
        }
        return counts;
    }

    private Answer<String> recording() {
        return invocation -> {
            String returned = (String) invocation.callRealMethod();
            Parameter[] parameters = invocation.getMethod().getParameters();
            List<String> arguments = new ArrayList<>();
            for (int i = 0; i < parameters.length; i++) {
                if (!(invocation.getArgument(i) instanceof ToolContext)) {
                    arguments.add(parameters[i].getName() + "=" + invocation.getArgument(i));
                }
            }
            calls.add(new ToolCall(invocation.getMethod().getName(), String.join(", ", arguments), returned));
            return returned;
        };
    }

    // ---- 정답 데이터 ----

    /** LLM에 미리 넣은 자료와 도구로 조회되는 데이터를 LLM이 받는 형식 그대로 보여 줍니다. */
    private String describeUser(Long userId) {
        StringBuilder text = new StringBuilder();
        text.append("#### LLM에 미리 넣은 자료 (사용자 정보 섹션)\n\n```text\n")
                .append(summarizer.format(summarizer.summarize(userId))).append("\n```\n\n");
        text.append("#### 도구로 조회되는 데이터\n\n");
        ToolContext context = new ToolContext(Map.of(UserDataTools.USER_ID, userId));
        for (SubscriptionResponseDto subscription : UserDataSummarizer.getInUseSubscriptionList(subscriptionService, userId)) {
            String last4 = subscription.phoneNumber().replaceAll("\\D", "");
            last4 = last4.substring(Math.max(0, last4.length() - 4));
            text.append("**회선 ").append(subscription.phoneNumber())
                    .append(" (LLM에는 ").append(UserDataSummarizer.maskPhone(subscription.phoneNumber())).append("로 보임)**\n\n");
            text.append("```text\n");
            text.append("[최근 ").append(RECORD_MONTHS).append("개월 사용량] 맨 위가 요약에 들어간 달이고, 도구는 그 아래 달만 돌려줍니다.\n");
            usageService.getMyUsageRecordList(userId, subscription.subscriptionId(), null, 0, RECORD_MONTHS).content()
                    .forEach(usage -> text.append("- ").append(UserDataSummarizer.formatUsage(usage)).append('\n'));
            text.append("\n[최근 ").append(RECORD_MONTHS).append("개월 청구서] 맨 위가 요약에 들어간 청구서입니다.\n");
            billingService.getMyBillingRecordList(userId, subscription.subscriptionId(), null, 0, RECORD_MONTHS).content()
                    .forEach(billing -> text.append("- ").append(UserDataSummarizer.formatBilling(billing)).append('\n'));
            text.append("\n[getAddons]\n").append(userDataTools.getAddons(last4, context)).append('\n');
            text.append("\n[getBundles]\n").append(userDataTools.getBundles(last4, context)).append('\n');
            text.append("\n[getRoamings]\n").append(userDataTools.getRoamings(last4, context)).append('\n');
            text.append("```\n\n");
        }
        return text.toString();
    }

    // ---- 보고서 ----

    private String markdown(List<Result> results, int total, Map<String, String> groundTruth,
            Map<String, Long> userIds, String abortReason) {
        StringBuilder md = new StringBuilder();
        long matched = results.stream().filter(Result::matched).count();
        md.append("# USER_DATA 답변 평가 결과\n\n");
        md.append("- 실행 시각: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append('\n');
        md.append("- 채팅 엔진: ").append(aiRuntimeProperties.resolvedChatEngine())
                .append(" / 모델: ").append(llmService.getModelName()).append('\n');
        md.append("- 기준일: ").append(BASE_TIME.toLocalDate()).append(" (Asia/Seoul 고정)\n");
        md.append("- 결과: ").append(matched).append(" / ").append(results.size()).append(" 일치");
        if (results.size() < total) {
            md.append(" (전체 ").append(total).append("개 중 ").append(results.size()).append("개만 실행)");
        }
        md.append('\n');
        md.append("- 판정 기준: PRELOADED·UNAVAILABLE은 도구 미호출, LOGIN_REQUIRED는 도구 미호출이면서 도구가 켜지지 않음, ")
                .append("TOOL은 기대 도구를 모두 호출(`a;b`는 각 1번 이상, `a*2`는 2번 이상). 오류는 불일치입니다. 답변 내용의 정확성은 아래 정답 데이터와 비교해 사람이 확인합니다.\n");
        if (abortReason != null) {
            md.append("\n> **중단됨:** ").append(abortReason).append('\n');
        }

        md.append("\n## 기대 처리별 결과\n\n| 기대 처리 | 일치 | 전체 |\n|---|---|---|\n");
        Map<String, List<Result>> byExpected = results.stream()
                .collect(Collectors.groupingBy(result -> result.question().expected(), LinkedHashMap::new, Collectors.toList()));
        byExpected.forEach((expected, group) -> md.append("| ").append(expected).append(" | ")
                .append(group.stream().filter(Result::matched).count()).append(" | ").append(group.size()).append(" |\n"));

        md.append("\n## 불일치 목록\n\n");
        List<Result> mismatched = results.stream().filter(result -> !result.matched()).toList();
        if (mismatched.isEmpty()) {
            md.append("없음\n");
        } else {
            md.append("| 번호 | 질문 | 사용자 | 기대 | 실제 |\n|---|---|---|---|---|\n");
            for (Result result : mismatched) {
                md.append("| ").append(result.question().number())
                        .append(" | ").append(cell(result.question().text()))
                        .append(" | ").append(userLabel(result.question()))
                        .append(" | ").append(cell(expectedLabel(result.question())))
                        .append(" | ").append(cell(actualLabel(result))).append(" |\n");
            }
        }

        md.append("\n## 사용자별 정답 데이터\n\n");
        groundTruth.forEach((key, text) -> md.append("### 사용자 ").append(key)
                .append(" (user_id=").append(userIds.get(key)).append(")\n\n").append(text));

        md.append("## 질문별 결과\n\n");
        for (Result result : results) {
            Question question = result.question();
            md.append("### ").append(question.number()).append(". ").append(question.text())
                    .append(" — ").append(result.matched() ? "일치" : "불일치").append("\n\n");
            md.append("- 사용자: ").append(userLabel(question)).append('\n');
            md.append("- 기대: ").append(expectedLabel(question));
            if (!question.note().isBlank()) {
                md.append(" (").append(question.note()).append(')');
            }
            md.append('\n');
            md.append("- 켜진 도구: ").append(result.enabledTools().isEmpty() ? "없음" : String.join(", ", result.enabledTools())).append('\n');
            md.append("- 실제 도구 호출: ").append(result.calls().isEmpty() ? "없음" : "").append('\n');
            for (ToolCall call : result.calls()) {
                md.append("  - `").append(call.name()).append('(').append(call.arguments()).append(")`\n\n")
                        .append("    ```text\n");
                call.returned().lines().forEach(line -> md.append("    ").append(line).append('\n'));
                md.append("    ```\n");
            }
            md.append("- 소요 시간: ").append(result.elapsedMillis()).append("ms\n");
            if (result.error() != null) {
                md.append("- 오류: `").append(result.error().replace('`', '\'')).append("`\n");
            }
            md.append("\n**답변**\n\n");
            String answer = result.answer() == null || result.answer().isBlank() ? "(답변 없음)" : result.answer();
            answer.lines().forEach(line -> md.append("> ").append(line).append('\n'));
            md.append('\n');
        }
        return md.toString();
    }

    private String csv(List<Result> results) {
        StringBuilder csv = new StringBuilder();
        csv.append(csvRow(List.of("번호", "질문", "사용자", "기대 처리", "기대 도구", "켜진 도구",
                "실제 도구 호출", "판정", "답변", "소요ms", "오류")));
        for (Result result : results) {
            Question question = result.question();
            csv.append(csvRow(List.of(question.number(), question.text(), userLabel(question), question.expected(),
                    question.expectedTool(), String.join(" ; ", result.enabledTools()),
                    result.calls().stream().map(call -> call.name() + "(" + call.arguments() + ")")
                            .collect(Collectors.joining(" ; ")),
                    result.matched() ? "일치" : "불일치",
                    result.answer() == null ? "" : result.answer(),
                    String.valueOf(result.elapsedMillis()),
                    result.error() == null ? "" : result.error())));
        }
        return csv.toString();
    }

    private String csvRow(List<String> values) {
        return values.stream()
                .map(value -> "\"" + (value == null ? "" : value).replace("\"", "\"\"") + "\"")
                .collect(Collectors.joining(",")) + "\r\n";
    }

    private String expectedLabel(Question question) {
        return question.expected() + (question.expectedTool().isBlank() ? " (도구 없음)" : " / " + question.expectedTool());
    }

    private String actualLabel(Result result) {
        if (result.error() != null) {
            return "오류: " + result.error();
        }
        String called = result.calls().isEmpty() ? "도구 호출 없음"
                : result.calls().stream().map(call -> call.name() + "(" + call.arguments() + ")").collect(Collectors.joining(", "));
        return called + (result.enabledTools().isEmpty() ? " / 켜진 도구 없음" : "");
    }

    private String userLabel(Question question) {
        return question.user().isEmpty() ? "비로그인" : question.user();
    }

    private String cell(String value) {
        return value.replace("|", "\\|").replaceAll("\\s+", " ");
    }

    // ---- 입력 ----

    private List<Question> readQuestions() throws IOException {
        List<String> lines = new ClassPathResource(QUESTIONS).getContentAsString(StandardCharsets.UTF_8).lines()
                .filter(line -> !line.isBlank()).toList();
        List<String> header = parseCsvLine(lines.getFirst().replace("﻿", ""));
        List<Question> questions = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            List<String> values = parseCsvLine(line);
            Map<String, String> row = new HashMap<>();
            for (int i = 0; i < header.size(); i++) {
                row.put(header.get(i), i < values.size() ? values.get(i).strip() : "");
            }
            questions.add(new Question(row.get("번호"), row.get("질문"), row.get("사용자"),
                    row.get("기대 처리"), row.get("기대 도구"), row.get("비고")));
        }
        return questions;
    }

    /** 따옴표로 감싼 값(쉼표·따옴표 포함)을 지원하는 한 줄 CSV 파서입니다. */
    private static List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        values.add(current.toString());
        return values;
    }

    /** 루트 .env를 읽고 OS 환경변수로 덮어씁니다. 파일이 없으면 OS 환경변수만 씁니다. */
    private static Map<String, String> loadEnv() {
        Map<String, String> env = new HashMap<>();
        Path file = Path.of(".env");
        if (Files.isRegularFile(file)) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String trimmed = line.strip();
                    int separator = trimmed.indexOf('=');
                    if (trimmed.isEmpty() || trimmed.startsWith("#") || separator < 1) {
                        continue;
                    }
                    String value = trimmed.substring(separator + 1).strip();
                    if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                            || value.startsWith("'") && value.endsWith("'"))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    env.put(trimmed.substring(0, separator).strip(), value);
                }
            } catch (IOException exception) {
                throw new IllegalStateException(".env를 읽지 못했습니다.", exception);
            }
        }
        env.putAll(System.getenv());
        return env;
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record Question(String number, String text, String user, String expected, String expectedTool, String note) {
    }

    private record ToolCall(String name, String arguments, String returned) {
    }

    private record Result(Question question, List<String> enabledTools, List<ToolCall> calls, String answer,
            String error, long elapsedMillis, boolean matched) {
    }
}
