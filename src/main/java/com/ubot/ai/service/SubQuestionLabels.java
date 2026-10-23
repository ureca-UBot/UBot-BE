package com.ubot.ai.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import kr.pe.bab2min.Kiwi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 답하지 못한 하위 질문을 고지에서 가리킬 때 쓰는 원문 조각을 다듬습니다.
 * 원문 조각은 원문을 연결어미·접속조사 뒤에서 자른 것이라("요금은 얼마고", "법이랑") 그대로 넣으면 문장이 어색해서,
 * 끝의 접속조사는 떼고 연결어미는 "-는지/-ㄴ지"로 바꿉니다("요금은 얼마인지", "몇 년까지 되는지").
 */
@Slf4j
@Component
public class SubQuestionLabels {
    private static final Kiwi.AnalyzeOption ANALYZE_OPTION = new Kiwi.AnalyzeOption(Kiwi.Match.allWithNormalizing);
    private static final String TRAILING_PUNCTUATION = "[\\s.?!？。,，~]+$";
    /** 명사 뒤에서 "와/과"처럼 다음 질문으로 잇는 조사입니다. Kiwi가 JKB(부사격)로 읽는 경우도 있어 형태로 함께 봅니다. */
    private static final Set<String> JOINERS = Set.of("랑", "이랑", "하고", "와", "과");
    /** 형용사·지정사 뒤에는 "-ㄴ지", 그 밖의 용언·선어말어미 뒤에는 "-는지"를 붙입니다. 있다·없다는 형용사여도 "-는지"입니다. */
    private static final String ADJECTIVE_ENDING = "ᆫ지";
    private static final String VERB_ENDING = "는지";

    private final Kiwi kiwi;

    public SubQuestionLabels(Kiwi kiwi) {
        this.kiwi = kiwi;
    }

    /** 형태소 분석 없이 끝의 문장 부호만 지웁니다. 단위 테스트처럼 Kiwi 모델이 없는 곳에서 씁니다. */
    public static SubQuestionLabels withoutMorphology() {
        return new SubQuestionLabels(null);
    }

    /** 원문 조각 끝의 문장 부호를 지우고 접속조사·연결어미를 정리합니다. 분석에 실패하면 문장 부호만 지운 조각을 씁니다. */
    public String label(String piece) {
        if (!StringUtils.hasText(piece)) {
            return piece;
        }
        String text = piece.strip().replaceAll(TRAILING_PUNCTUATION, "");
        if (kiwi == null || text.isEmpty()) {
            return text;
        }
        try {
            return trimConnective(text);
        } catch (Exception exception) {
            // 네이티브 코드는 선언하지 않은 checked 예외도 던지므로 Exception으로 받습니다. 고지 문구 때문에 답변을 실패시키지 않습니다.
            log.warn("고지용 원문 조각을 다듬지 못했습니다: {}", exception.getClass().getSimpleName());
            return text;
        }
    }

    private String trimConnective(String text) {
        Kiwi.Token[] tokens = kiwi.tokenize(text, ANALYZE_OPTION);
        if (tokens.length < 2) {
            return text;
        }
        Kiwi.Token last = tokens[tokens.length - 1];
        String tag = Kiwi.POSTag.toString(last.tag);
        if (tag.startsWith("JC") || (tag.startsWith("JKB") && JOINERS.contains(last.form))) {
            return text.substring(0, Math.min(last.position, text.length())).stripTrailing();
        }
        // "어떻게 하고"처럼 끝의 "-고"를 종결어미로 읽는 경우도 연결어미와 같게 봅니다.
        if (!tag.startsWith("EC") && !(tag.startsWith("EF") && "고".equals(last.form))) {
            return text;
        }
        // 마지막 어절만 형태소를 다시 이어 붙여 활용형을 맞춥니다("얼마이고" → "얼마인지").
        int wordStart = text.lastIndexOf(' ', Math.max(last.position - 1, 0)) + 1;
        List<Kiwi.JoinableToken> word = new ArrayList<>();
        Kiwi.Token previous = null;
        for (int index = 0; index < tokens.length - 1; index++) {
            if (tokens[index].position >= wordStart) {
                word.add(new Kiwi.JoinableToken(tokens[index]));
                previous = tokens[index];
            }
        }
        if (previous == null || !isConjugable(Kiwi.POSTag.toString(previous.tag))) {
            return text;
        }
        word.add(new Kiwi.JoinableToken(usesAdjectiveEnding(previous) ? ADJECTIVE_ENDING : VERB_ENDING, Kiwi.POSTag.ec));
        return text.substring(0, wordStart) + kiwi.join(word.toArray(new Kiwi.JoinableToken[0]));
    }

    private static boolean isConjugable(String tag) {
        return tag.startsWith("VV") || tag.startsWith("VA") || tag.startsWith("VX") || tag.startsWith("VCP")
                || tag.startsWith("VCN") || tag.startsWith("XSV") || tag.startsWith("XSA") || tag.startsWith("EP");
    }

    private static boolean usesAdjectiveEnding(Kiwi.Token token) {
        String tag = Kiwi.POSTag.toString(token.tag);
        boolean adjective = tag.startsWith("VA") && !"있".equals(token.form) && !"없".equals(token.form);
        return adjective || tag.startsWith("VCP") || tag.startsWith("VCN") || tag.startsWith("XSA");
    }
}
