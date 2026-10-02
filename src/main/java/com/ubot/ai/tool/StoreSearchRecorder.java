package com.ubot.ai.tool;

import com.ubot.ai.dto.StoreMapResult;
import java.util.Optional;

/**
 * 요청 하나에서 매장 조회 도구가 찾은 결과를 보관합니다.
 * toolContext로 도구에 전달되며, LLM 호출이 끝난 뒤 AiService가 결과를 꺼냅니다.
 */
public class StoreSearchRecorder {

    // 도구 실행과 결과 확인은 한 요청 안에서 일어나지만, 다른 스레드에서 읽어도 최신 값이 보이게 합니다.
    private volatile StoreMapResult result;

    /** 도구가 여러 번 호출되면 마지막 성공 결과로 덮어씁니다. */
    public void record(StoreMapResult value) {
        result = value;
    }

    public Optional<StoreMapResult> result() {
        return Optional.ofNullable(result);
    }
}
