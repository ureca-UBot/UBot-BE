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
}
