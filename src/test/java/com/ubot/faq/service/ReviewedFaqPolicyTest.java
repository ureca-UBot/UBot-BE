package com.ubot.faq.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ubot.faq.policy.ReviewedFaqPolicy;
import com.ubot.faq.policy.ReviewedFaqPolicy.Offer;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewedFaqPolicyTest {
    private final ReviewedFaqPolicy policy = new ReviewedFaqPolicy();
    private final Offer definition = new Offer(14,"eSIM이 무엇인가요?",
            "물리적인 SIM 카드 없이 사용하는 디지털 SIM입니다.",.88,true,true);
    private final Offer port = new Offer(3,"번호이동을 하면 기존 번호를 그대로 사용할 수 있나요?",
            "네. 번호이동 시 기존 전화번호를 그대로 유지할 수 있습니다.",.93,true,true);
    private final Offer device = new Offer(6,"기기만 변경하면 전화번호도 바뀌나요?",
            "기기만 변경하는 경우 기존 전화번호는 그대로 유지됩니다.",.90,true,true);
    private final Offer competitor = new Offer(322,"eSIM은 어디에서 발급받나요?","매장에서 신청하세요.",.70,false,true);

    @Test
    void negatedPortingAndNewNumberRequestsCannotUseTheNumberRetentionAnswer() {
        for (String question : List.of(
                "번호이동 없이 통신사를 옮겨도 같은 전화번호를 쓸 수 있는지 설명 부탁드립니다.",
                "번호 이동을 하지 않고 다른 통신사에서 기존 번호를 사용할 수 있나요?",
                "번호이동을 안 해도 전화번호를 유지할 수 있어요?",
                "번호이동이 아닌 경우에도 기존 번호를 쓸 수 있나요?",
                "통신사를 바꾸고 새 전화번호로 가입할 수 있나요?",
                "다른 통신사로 옮길 때 다른 번호를 받을 수 있어요?")) {
            assertThat(policy.supports(question)).as(question).isFalse();
            assertThat(policy.decide(question, List.of(port, competitor)).faqId()).as(question).isNull();
        }
        assertThat(policy.decide("번호이동을 해도 지금 전화번호를 유지할 수 있어요?",
                List.of(port, competitor)).faqId()).isEqualTo(3);
    }

    @Test void extraRequestsAndHistoricalFalseCacheDoNotMatch() {
        for (String q : List.of(
                "실물 유심과 이심을 동시에 사용할 수 있는지 궁금해요. 지원 단말 목록도 부탁드립니다.",
                "통신사를 옮겨도 번호는 유지되나요? 남은 약정 비용도 알고 싶어요.",
                "유심만 바꾸면 번호는 유지됩니까? 새 유심을 구입할 곳도 안내 부탁드립니다.",
                "eSIM 뜻을 알려줘 발급 방법도 알려줘", "내 폰에서 eSIM과 유심을 같이 쓸 수 있나요?",
                "통신사를 옮기면서 새 전화번호를 받고 싶어요.", "그거 번호는 그대로예요?")) {
            assertThat(policy.supports(q)).as(q).isFalse();
        }
        assertThat(policy.supports("안녕하세요! eSIM이라는 용어의 뜻이 궁금해요!")).isTrue();
        assertThat(policy.supports("안녕하세요. eSIM과 일반 유심을 동시에 쓰는 게 가능한가요?")).isTrue();
    }

    @Test void explicitDeviceConditionCanRejectUnchangedPortAlternativeButKeepsOtherCompetitors() {
        String q = "기기만 새 것으로 바꾸면 전화번호는 그대로 유지되나요?";
        assertThat(policy.decide(q,List.of(port,device,competitor)).faqId()).isEqualTo(6);
        var closeUnreviewed = new Offer(8,"통신사는 그대로 두고 휴대폰만 바꿀 수 있나요?","기기변경 가능합니다.",.89,false,false);
        assertThat(policy.decide(q,List.of(port,device,closeUnreviewed)).faqId()).isNull();
    }

    @Test void contentChangesRevocationAndMissingCompetitorFailClosed() {
        String q = "eSIM이 뭐야!";
        assertThat(policy.decide(q,List.of(definition,competitor)).faqId()).isEqualTo(14);
        var changed = new Offer(14,definition.question(),"변경된 내용",.99,true,true);
        assertThat(policy.decide(q,List.of(changed,competitor)).faqId()).isNull();
        var revoked = new Offer(14,definition.question(),definition.answer(),.99,true,false);
        assertThat(policy.decide(q,List.of(revoked,competitor)).faqId()).isNull();
        var unregistered = new Offer(14,definition.question(),definition.answer(),.99,false,true);
        assertThat(policy.decide(q,List.of(unregistered,competitor)).faqId()).isNull();
        assertThat(policy.decide(q,List.of(definition)).faqId()).isNull();
    }

    @Test void changedAlternativeCannotBeSilentlyRemovedFromMargin() {
        String q = "기기만 새 것으로 바꾸면 전화번호는 그대로 유지되나요?";
        var changedPort = new Offer(port.id(),port.question(),"새로운 답변",port.score(),true,true);
        assertThat(policy.decide(q,List.of(changedPort,device,competitor)).faqId()).isNull();
    }

    @Test void nonFiniteScoreAndUnknownMeaningCannotBeApproved() {
        assertThat(policy.decide("eSIM이 뭐야?",List.of(definition,
                new Offer(77,"경쟁 질문","답변",Double.NaN,false,true))).faqId()).isNull();
        assertThat(policy.decide("eSIM의 최대 저장 개수는?",List.of(definition,competitor)).faqId()).isNull();
    }

    @Test void knownVocabularyCannotCombineDifferentAnswerScopes() {
        for (String q : List.of("eSIM 뜻이 뭐야 전화도 가능한가요?",
                "번호이동해도 번호를 유지하면서 eSIM도 동시에 쓸 수 있어요?",
                "유심을 교체하고 휴대폰도 교체하면 번호는 그대로인가요?",
                "eSIM이 무슨 의미인지와 유심하고 같이 쓸 수 있는지 궁금해요.",
                "유심과 이심을 동시에 쓰면서 두 전화를 같이 받을 수 있나요?",
                "제가 쓰는 단말에서 이심과 유심을 같이 사용할 수 있는지 알려주세요.",
                "내가 이용하는 폰에서 이심과 유심을 함께 쓸 수 있어요?",
                "기기만 바꾸면서 전화번호도 바꾸고 싶어요.",
                "통신사를 옮기면서 전화번호도 변경하려고 해요.")) {
            assertThat(policy.supports(q)).as(q).isFalse();
        }
    }

    @Test void reviewedInflectionsStillRequireTheOriginalScoreAndMargin() {
        assertThat(policy.supports("기기 변경만 하는데 휴대전화 번호는 그대로 남을까요?")).isTrue();
        assertThat(policy.supports("유심을 교체해도 지금 쓰는 전화번호가 동일하게 유지됩니까?")).isTrue();
        String question = "eSIM 개념을 짧게 풀어서 설명해주시겠어요?";
        assertThat(policy.decide(question, List.of(definition, competitor)).faqId()).isEqualTo(14);
        var lowScore = new Offer(14, definition.question(), definition.answer(), .74, true, true);
        assertThat(policy.decide(question, List.of(lowScore, competitor)).faqId()).isNull();
        var close = new Offer(322, competitor.question(), competitor.answer(), .87, false, true);
        assertThat(policy.decide(question, List.of(definition, close)).faqId()).isNull();
    }

    @Test void inflectionExpansionDoesNotApproveEarlierContextOrMixedDeviceAndCarrierChanges() {
        for (String question : List.of(
                "eSIM은 그때 말한 SIM과 같은 개념인가요?",
                "기기만 변경하고 통신사도 변경할 때 기존 번호는 유지될까요?",
                "기기 변경을 한 다음 그 번호도 계속 쓸 수 있나요?",
                "번호이동 후 기존 번호를 유지하고 싶어요 그 회사도 같은가요?")) {
            assertThat(policy.supports(question)).as(question).isFalse();
        }
    }
}
