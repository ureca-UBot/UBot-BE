package com.ubot.ai.tool;

import com.ubot.ai.dto.UserDataCards;
import com.ubot.ai.dto.UserDataCards.BillingCard;
import com.ubot.ai.dto.UserDataCards.UsageCard;
import com.ubot.billing.dto.response.BillingRecordResponseDto;
import com.ubot.usage.dto.response.UsageRecordResponseDto;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 요청 하나에서 가입 정보 도구가 조회한 사용량·청구서를 보관합니다.
 * toolContext로 도구에 전달되며, LLM 호출이 끝난 뒤 AiService가 화면용 카드로 꺼냅니다.
 */
public class UserDataRecorder {

    /** 출력 검사 근거로 덧붙이는 도구 조회 결과 섹션의 제목입니다. 출력 가드는 이 섹션이 있으면 회원 기록이 제공된 것으로 봅니다. */
    public static final String SECTION_TITLE = "사용자 정보(도구 조회 결과)";

    // 도구 실행과 결과 확인이 다른 스레드에서 일어나도 기록이 빠지지 않게 합니다.
    private final List<UsageCard> usages = new CopyOnWriteArrayList<>();
    private final List<BillingCard> billings = new CopyOnWriteArrayList<>();

    public void recordUsage(String maskedPhoneNumber, UsageRecordResponseDto usage) {
        usages.add(new UsageCard(maskedPhoneNumber, usage));
    }

    public void recordBilling(String maskedPhoneNumber, BillingRecordResponseDto billing) {
        billings.add(BillingCard.of(maskedPhoneNumber, billing));
    }

    public UserDataCards cards() {
        return new UserDataCards(List.of(), usages, billings);
    }

    /**
     * 도구가 LLM에 돌려준 기록을 같은 표기로 만듭니다. 프롬프트 자료에 없는 지난 달 값을 출력 검사가 근거로 보게 할 때 씁니다.
     * 조회한 기록이 없으면 빈 문자열입니다.
     */
    public String describe() {
        StringBuilder text = new StringBuilder();
        usages.forEach(card -> text.append(card.phoneNumber()).append(" 회선\n")
                .append(UserDataSummarizer.formatUsage(card.usage())).append('\n'));
        billings.forEach(card -> text.append(card.phoneNumber()).append(" 회선\n")
                .append(UserDataSummarizer.formatBilling(card.billing())).append('\n'));
        return text.toString();
    }
}
