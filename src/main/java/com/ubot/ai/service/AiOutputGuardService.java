package com.ubot.ai.service;

import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.ContextSection;
import com.ubot.ai.dto.OutputGuardResult.Reason;
import com.ubot.ai.dto.OutputGuardResult;
import com.ubot.ai.dto.StoreMapResult;
import com.ubot.ai.dto.UserDataCards.BillingCard;
import com.ubot.ai.dto.UserDataCards;
import com.ubot.ai.tool.AiTool;
import com.ubot.ai.tool.UserDataRecorder;
import com.ubot.ai.tool.UserRecordClaims;
import com.ubot.billing.enums.PaymentMethod;
import com.ubot.billing.enums.PaymentStatus;
import com.ubot.chat.context.UserIntentHandler;
import com.ubot.common.GlobalException;
import com.ubot.faq.enums.Intent;
import com.ubot.forbiddenword.exception.ForbiddenWordErrorCode;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.prompt.format.FaqAnswerFormat;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.BreakIterator;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 파싱된 답변의 기존 규칙에서 문제가 탐지되면 답변 전체를 거절합니다. 모든 의미 오류를 판정하지는 않습니다. */
@Slf4j
@Service
public class AiOutputGuardService {

    private static final String UNANSWERED_SCOPE_NOTICE = "에 대한 내용은 이번 답변에서 안내하지 못했습니다.";
    private static final String DECIMAL_NUMBER = "[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?";
    private static final String MONEY_UNIT = "(?:억|만|천|백|십)";
    private static final String WON_AMOUNT = "(?:" + DECIMAL_NUMBER + "\\s*" + MONEY_UNIT
            + "\\s*(?:원\\s*)?)*" + DECIMAL_NUMBER + "\\s*(?:" + MONEY_UNIT + "\\s*)?원";
    private static final Pattern MONEY_COMPONENT = Pattern.compile(
            "(" + DECIMAL_NUMBER + ")\\s*(" + MONEY_UNIT + ")?\\s*(?:원)?");
    private static final Pattern QUANTITY = Pattern.compile(
            "(?<![\\d.,])(?:(?<money>" + WON_AMOUNT + ")|(?<value>" + DECIMAL_NUMBER + ")\\s*"
                    + "(?<unit>퍼센트|%|개월|년|일|시간|분|기가바이트|메가바이트|GB|MB|km|m))"
                    + "(?![a-zA-Z0-9])", Pattern.CASE_INSENSITIVE);
    private static final Pattern METRIC = Pattern.compile(
            "할인(?:율|금액)?|환불(?:율|금액)?|월정액|(?:월\\s*)?요금|수수료|위약금|배송비"
                    + "|(?:약정|처리|개통)\\s*기간|납부\\s*기한|유효\\s*기간|데이터(?:\\s*(?:제공량|용량))?"
                    + "|건당(?:\\s*요금)?|반경|거리");
    private static final Pattern MONEY_SELECTOR = Pattern.compile(
            "(?<![0-9.])([0-9]+(?:\\.[0-9]+)?)\\s*(회선|GB|MB|Mbps|Gbps)(?![a-zA-Z])", Pattern.CASE_INSENSITIVE);
    private static final Pattern RECORDED_DATE = Pattern.compile(
            "([0-9]{4})(?:-|년\\s*)([0-9]{1,2})(?:-|월\\s*)([0-9]{1,2})(?:일)?");
    private static final Pattern INSTALLMENT_HISTORY = Pattern.compile("([0-9]+)\\s*개월\\s*중\\s*([0-9]+)\\s*개월\\s*$");
    private static final Pattern INSTALLMENT_REMAINING = Pattern.compile("([0-9]+)\\s*개월\\s*(?:남|잔여)");
    private static final Pattern RECORDED_PHONE = Pattern.compile("([0-9]{4})\\s*회선|\\*{2,}-?([0-9]{4})(?![0-9])");
    private static final Pattern RECORDED_OTHER_ACTOR = Pattern.compile("친구|동료|이웃|배우자|자녀|타인|다른\\s*(?:고객|회원|사용자|사람)");
    private static final Pattern RECORDED_INSTALLMENT_AMOUNT = Pattern.compile(
            "할부(?:금)?(?:의)?\\s*(?:월|매월|한\\s*달)\\s*(?:납부액|납부금|납입액|납입금|금액)"
                    + "(?:은|는|이|가|[:：])?\\s*(?<amount>" + WON_AMOUNT + ")");

    private static final Pattern QUALIFIED_NUMBER = Pattern.compile(
            "(?:이|가|은|는)?\\s*(?:아니|아닙|아닌)|\\s*(?:으로|로)?\\s*잘못|\\s*(?:인가요|입니까|맞나요)");
    private static final Pattern UNCONFIRMED_QUANTITY = Pattern.compile(
            "^(?:(?!입니다|됩니다|적용되|제공되)[^,;.!?。！？\\n0-9]){0,64}"
                    + "(?:확인할\\s*수\\s*없|알\\s*수\\s*없"
                    + "|(?:명시|기재|포함)[^,;.!?。！？\\n0-9]{0,12}(?:않|없)"
                    + "|(?:이라|라고)\\s*했을\\s*경우)"
                    + "|^\\s*(?:(?:입니다|예요|이에요|이다)\\s*)?['\"’”`＇＂」』]?\\s*"
                    + "(?:(?:이라는|라는|이란|란)\\s*(?:안내|문구|표현|설명|정보|주장|답변|것|내용)"
                    + "|(?:이라고|라고|으로|로)\\s*(?:안내|설명|표현|답변|표기|기재)(?:한|된|드린)\\s*(?:것|내용))"
                    + "(?:은|는|이|가)?\\s*(?:잘못(?:됐|되었)(?:습니다|어요|으며|고|지만)|틀렸(?:습니다|어요|으며|고|지만)"
                    + "|(?:잘못된|틀린|부정확한)\\s*(?:내용|정보|설명|안내)(?:입니다|이에요|예요|이며|이고|이지만)"
                    + "|부정확(?:합니다|해요|하며|하고)|사실(?:이|은)?\\s*아(?:닙니다|니에요|니며|니고|니라)"
                    + "|(?:맞지|정확하지)\\s*않(?:습니다|아요))(?=\\s|[,;.!?。！？]|$)");
    private static final Pattern DERIVED_OR_EXAMPLE = Pattern.compile(
            "(?:계산|차감|합산)\\s*(?:결과|금액|요금|하면|하여|해서|한|된|됩니다|해요|했|으로|상|시)"
                    + "|곱하|나누|(?:할인|적용)\\s*후|예를\\s*들(?:어|면)"
                    + "|가정\\s*(?:하면|하여|해서|한|된|으로|하에|하에서는|시|[:：])");
    private static final Pattern QUANTITY_CLAUSE_BOUNDARY = Pattern.compile(
            "(?<![0-9])[,，]|[,，](?![0-9])|[;；]"
                    + "|(?:입니다|이에요|예요|합니다|됩니다|이며|이고|지만|으나|인데)\\s+(?=\\S)");
    private static final String PRICE_REFERENCE = "[^.!?。！？\\n]{0,24}"
            + "(?:금액|요금|할인(?:액|율)?|[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?\\s*(?:원|%))"
            + "(?:을|를|은|는)?\\s*(?:[0-9]+\\s*배(?:로)?\\s*)?";
    private static final Pattern REQUESTED_PRICE_REWRITE = Pattern.compile(
            "(?:문서|FAQ|자료|실제)" + PRICE_REFERENCE
                    + "(?:낮춰|줄여|높여|늘려|바꿔|변경해)[^.!?。！？\\n]{0,16}(?:답|안내|출력)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PRICE_REWRITE_ACCEPTED = Pattern.compile(
            "(?:원문|문서|FAQ|자료|실제)" + PRICE_REFERENCE
                    + "(?:낮추|높이|바꾸|변경하)[^.!?。！？\\n]{0,24}(?:답변|안내|출력)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern QUESTION_END = Pattern.compile("(?:[?？]|인가요|입니까|나요|까요)\\s*[.!。！]?$");
    private static final String ASSERTED_PAST_ENDING =
            "(?:습니다|어요|는데요|는데|거든요|거든|으니까|으니|고요|고|으며|네요|지요|군요|죠|(?:어|네|지|다|음)(?![가-힣]))";
    private static final Pattern ASSERTED_CLAUSE_END = Pattern.compile(
            "(?:니다|어요|아요|예요|죠|되며|됨|(?:했|됐|되었|하였|드렸|마쳤|끝냈|끝났)"
                    + ASSERTED_PAST_ENDING + "|(?:있|없)(?:고|으며|는데(?:요)?|으니(?:까)?))$");
    private static final Pattern SOURCE_DIRECTIVE = Pattern.compile(
            "실제\\s*(?:요금|정보|자료|내용|사실)(?:과|와)?\\s*달라도"
                    + "|(?:이|해당)\\s*(?:FAQ|문서|자료)(?:를|을)\\s*읽(?:으면|고)"
                    + "|\\[(?:운영\\s*공지|도구\\s*성공|API\\s*성공|SYSTEM|DEVELOPER)\\]"
                    + "[^.!?。！？\\n]{0,80}(?:간주|출력해|생략해|무시해|답해|바꿔)"
                    + "|(?:이전|기존|상위|시스템)\\s*(?:지시|지침|명령|규칙)(?:을|를)?\\s*(?:(?:모두|전부)\\s*)?"
                    + "(?:무시하고|무시한\\s*채|무시한\\s*상태로)[^.!?。！？\\n]{0,120}"
                    + "(?:답|대답|응답|출력)(?:하세요|하라|해(?:라|줘)?|하시오)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FREE_BENEFIT = Pattern.compile("무료");
    private static final Pattern FREE_DECLARATION = Pattern.compile(
            "^\\s*(?:입니|예요|이다|이지만|이며|이므로|지만|라고\\s*(?:답|안내)"
                    + "|로\\s*(?:제공|이용|신청)|(?:부가)?서비스"
                    + "|라\\s|체험|[가-힣]+(?:입니|예요)"
                    + "|[가-힣]+(?:\\s+[가-힣]+){0,2}\\s+서비스)");
    private static final Pattern DENIED_BENEFIT = Pattern.compile(
            "^(?:(?!지만|으나)[^,;.!?。！？\\n]){0,24}?"
                    + "(?:아니|아닙|할\\s*수\\s*없|허용되지|되지\\s*않)");
    private static final Pattern INCLUDED_BENEFIT = Pattern.compile("기본\\s*제공|추가\\s*(?:과금|요금)\\s*없");
    private static final Pattern UNIVERSAL_BENEFIT = Pattern.compile(
            "(?:모든|전체|전부)[^.!?。！？\\n]{0,16}(?:서비스|요금|통화료|이용료)");
    private static final Pattern CONDITIONAL_BENEFIT = Pattern.compile("^\\s*(?:인지|라면|이면|일\\s*경우)");
    private static final Pattern INITIAL_AUTHENTICATION = Pattern.compile(
            "(?:한\\s*번|최초|먼저)\\s*(?:본인\\s*)?인증(?:하면|한\\s*후|\\s*후)");
    private static final Pattern REAUTHENTICATION_WINDOW = Pattern.compile(
            "([0-9]+)\\s*(분|시간|초)\\s*동안\\s*다시\\s*(?:본인\\s*)?인증\\s*없이");
    private static final Pattern SOURCE_AUTHENTICATION = Pattern.compile(
            "(?:한\\s*번|최초|먼저)\\s*(?:본인\\s*)?인증하면");
    private static final Pattern DOCUMENT = Pattern.compile(
            "본인\\s*(?:인증|확인)|신분증|위임장|법정대리인\\s*동의|필수\\s*조건");
    private static final Pattern REQUIREMENT_ABSENCE = Pattern.compile("^\\s*(?:은|는|이|가)?\\s*(?:없|불필요)");
    private static final Pattern SOURCE_POLICY_CLAIM = Pattern.compile(
            "(?:무료|면제|무제한)\\s*(?:규정|정책)(?:에|을|를)?\\s*(?:따라|적용|우선)"
                    + "|(?:제공(?:된|한)\\s*)?(?:FAQ|문서|자료)(?:는|은|가)?\\s*(?:구버전|오래된\\s*(?:자료|문서))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REPORTED_POLICY_REQUEST = Pattern.compile(
            "^\\s*[^,;.!?。！？\\n]{0,24}고자\\s*하셨");
    private static final Pattern ZERO_AFTER_CHARGE = Pattern.compile(
            "(?:위약금|수수료|요금|이용료)(?:이|가)?\\s*(?:있어도|발생해도|부과되어도)"
                    + "[^,;.!?。！？\\n]{0,16}(?:0\\s*원|무료)");
    private static final Pattern FACT_FOLLOWUP = Pattern.compile(
            "^(?:(?:해당|그|위)\\s*규정|(?:다만[,，]?\\s*)?(?:이|그)(?:는|것은)\\s*(?:FAQ|문서|자료|가정))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WAIVED = Pattern.compile(
            "생략|면제|우회(?:할\\s*수\\s*있|해도\\s*됩|하겠|했|해\\s*드리)|없이|불필요"
                    + "|필요(?:가|는)?\\s*없|하지\\s*않아도|안\\s*해도");
    private static final Pattern DENIED_WAIVER = Pattern.compile(
            "(?:생략|면제|우회)(?:할|이|은|는|을)?\\s*(?:수\\s*없|불가|안\\s*되|되지\\s*않|하지\\s*않|안\\s*됩)"
                    + "|(?:생략|면제|우회)(?:을|를|은|는)?\\s*(?:하지\\s*마|금지|허용되지)"
                    + "|우회(?:해서는|해선|하면)\\s*안|우회(?:를|는)?\\s*(?:막|차단|금지|방지|예방)"
                    + "|없이(?:(?!지만|으나)[^,;.!?。！？\\n]){0,80}?"
                    + "(?:불가능|가능하지|할\\s*수\\s*없|범위(?:를|에서)?\\s*(?:벗어나|벗어납))"
                    + "|없이는|없으면|없을\\s*경우");
    // 실제 처리의 완료 표현만 묶습니다. 가입 안내·이벤트 종료 등 다른 대상은 포함하지 않습니다.
    private static final String FINISHED_OPERATION =
            "(?:\\s*(?:절차|처리|작업|신청|접수))?\\s*(?:을|를|이|가|은|는)?\\s*"
                    + "(?:(?:이미|방금|정상적으로|성공적으로|모두|전부)\\s*){0,3}"
                    + "(?:마쳤|끝냈|끝났|마무리(?:했|됐|되었)|다\\s*(?:됐|되었))";
    private static final String TRANSACTION_ACTION = "(?:환불|해지|가입|개통|결제|납부|변경|접수|신청|설치|복구|발송)";
    private static final Pattern GUIDANCE_COMPLETION = Pattern.compile(TRANSACTION_ACTION
            + "(?:\\s*(?:방법|절차))?\\s*(?:을|를|의|에\\s*(?:대한|관한))?\\s*(?:안내|설명)(?:을|를)?\\s*"
            + "(?:(?:마무리|완료)?했|(?:해\\s*)?드렸|마쳤|끝냈)(?:" + ASSERTED_PAST_ENDING + ")?$");
    private static final String TRANSACTION_RESULT =
            "(?:완료(?:했|되|됐|하였|된|입니다|예요)|완료(?:\\s*상태)?(?:가|는)?\\s*확인(?:이)?\\s*가능"
                    + "|완료\\s*문자(?:를)?\\s*받았|처리(?:했|되었|해\\s*드렸)|(?:해|하여)\\s*드렸|(?<!못)했" + ASSERTED_PAST_ENDING
                    + "|처리\\s*중입니다)";
    private static final Pattern TRANSACTION_CLAIM = Pattern.compile(
            TRANSACTION_ACTION + "(?:" + FINISHED_OPERATION + "|[^.!?。！？\\n]{0,30}?" + TRANSACTION_RESULT + ")");
    private static final Pattern TRANSACTION_RESULT_CLAIM = Pattern.compile(FINISHED_OPERATION + "|" + TRANSACTION_RESULT);
    private static final Pattern PERSONAL_BILLING_CLAIM = Pattern.compile(
            "(?:(?:회원|고객|사용자)(?:님)?|귀하|당신|본인)(?:의)?\\s*"
                    + "(?:(?:이번\\s*(?:달|월)|현재|실제)\\s*)?"
                    + "(?:청구(?:\\s*(?:내역|서|금액|요금))?|고지서)"
                    + "(?:[^,;.!?。！？\\n]|(?<=\\d),(?=\\d)){0,32}?(?:포함|반영|청구)(?:되어|되었|됐|된)");
    private static final Pattern QUERY_CLAIM = Pattern.compile(
            "(?:조회|검색|확인)(?:" + FINISHED_OPERATION + "|(?:\\s*[을를이가])?\\s*"
                    + "(?:했" + ASSERTED_PAST_ENDING + "|하였|됐|되었|완료(?:했|되|하였)"
                    + "|(?:해|하여)\\s*드렸|(?:해\\s*드리고|하고|하는)\\s*(?:있|중)|중입니다"
                    + "|(?:해|하여)[^.!?。！？\\n]{0,18}(?:해|하여)\\s*드렸))");
    private static final Pattern STORE_PROGRESS_CLAIM = Pattern.compile(
            "(?:찾아|골라|선별해)[^.!?。！？\\n]{0,18}?(?:안내|소개)(?:해|하여)\\s*드리고\\s*(?:있|중)");
    private static final Pattern ACCOUNT_RECORD = Pattern.compile(
            "(?:예약|청구|납부|환불|가입|약정|할부|결합|부가서비스|통화)\\s*"
                    + "(?:정보|내역|상태|시간|날짜|만료일|종료일|금액|설정|이력|기록|여부)"
                    + "|사용량|미납|명의자|이메일|전화번호");
    private static final Pattern PERSONAL_VALUE_REQUEST = Pattern.compile(
            "(?<![가-힣])(?:제(?:가|의)?|내(?:가)?|저(?:는|도|의)?|본인(?:의)?)\\s*"
                    + "(?:(?:지금|현재|이번\\s*달에?|지난\\s*달에?|밀린|쓰는|사용\\s*중인|무슨|어떤)\\s*){0,3}"
                    + "(?:요금|데이터|테더링|소액\\s*결제)"
                    + "|(?:이번|지난)\\s*(?:달|월)(?:에|의)?\\s*요금(?!제)"
                    + "|(?:요금(?!제)|데이터|테더링)[^.!?。！？\\n]{0,12}"
                    + "(?:몇\\s*(?:GB|MB|기가|메가)|얼마(?:나)?)[^.!?。！？\\n]{0,12}"
                    + "(?:남|나왔|나온|썼|쓴|사용했|더\\s*쓸)", Pattern.CASE_INSENSITIVE);
    private static final String QUERY_HELP = "(?:드리겠(?:습니다|어요)|드립니다|드려요"
            + "|드리(?:고|며)(?!\\s*있)(?=\\s|[,;.!?。！？\\n]|$)"
            + "|드릴\\s*(?:게요|수\\s*있(?:습니다|어요|도록))|드리고\\s*있(?:습니다|어요)"
            + "|드리는\\s*(?:것|서비스)(?:이|은|가|는)?\\s*가능(?:합니다|해요|(?=하지만|하며|하고))"
            + "|드리는\\s*서비스(?:를)?\\s*제공(?:합니다|해요))";
    private static final Pattern QUERY_PROMISE = Pattern.compile(
            "(?:조회|검색|확인)\\s*(?:[을를]\\s*)?(?:(?:진행|수행)\\s*)?"
                    + "(?:하겠(?:습니다|어요)|(?:해|하여)?\\s*" + QUERY_HELP + ")"
                    + "|(?:조회|검색|확인)\\s*(?:해|하여|(?:한\\s*)?후|하고|해서)"
                    + "(?!\\s*(?:드리기|주(?:시면|신|셔야)))[^.!?。！？\\n]{0,64}?"
                    + "(?:안내|설명|답변|확인)(?:해|하여)?\\s*" + QUERY_HELP
                    + "|(?:찾아|골라)\\s*" + QUERY_HELP
                    + "|(?:찾아|골라)\\s*드리(?:고|며)[^.!?。！？\\n]{0,64}?"
                    + "(?:안내|설명|답변)(?:해|하여)?\\s*" + QUERY_HELP
                    + "|(?:조회|검색|확인)할\\s*수\\s*있도록[^,;.!?。！？\\n]{0,48}?도와\\s*" + QUERY_HELP
                    + "|(?:조회|검색)(?:해|하여)\\s*드리기\\s*위(?:해|해서)"
                    + "|(?:" + ACCOUNT_RECORD.pattern() + ")(?:을|를|은|는|도|에\\s*대해)?\\s*"
                    + "(?:안내|알려)(?:해|하여)?\\s*" + QUERY_HELP);
    private static final Pattern QUERY_CONNECTIVE_END = Pattern.compile(
            "(?:드리(?:고|며)|(?:가능|필요)(?:하며|하고))$");
    private static final Pattern QUERY_FACT_CONNECTIVE_END = Pattern.compile(
            "(?<stem>[가-힣]*(?:하|되|기|이)|있|없)(?:고|으며|며|지만|으나)$");
    private static final Pattern QUERY_LOCKOUT_SUBJECT = Pattern.compile(
            "(?:(?<system>앱|계정|유심)(?:의)?\\s*)?(?<kind>비밀번호|암호|PIN)", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUERY_LOCKOUT_NOTICE = Pattern.compile(
            "^(?:" + QUERY_LOCKOUT_SUBJECT.pattern() + "(?:를|을)?\\s*)?"
                    + "(?<prefixSeries>연속(?:으로)?\\s*)?(?<attempts>[0-9]+)\\s*(?:회|번)\\s*(?<series>연속)?\\s*"
                    + "(?:틀리면|잘못\\s*입력하면|오류\\s*시|실패\\s*시)\\s*"
                    + "(?<duration>[0-9]+)\\s*(?<unit>분|시간)(?:\\s*동안|간)?\\s*잠깁니다[.!。！]?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUERY_CONTINUATION = Pattern.compile(
            "(?:안내|설명|답변|확인|제공)(?:해|하여)?\\s*" + QUERY_HELP);
    private static final Pattern QUERY_SOURCE_REPORT = Pattern.compile(
            "(?:라고|다고)\\s*(?:(?:설명|명시|기재)(?:합니다|되어\\s*있습니다|하고\\s*있습니다)"
                    + "|되어\\s*있습니다)\\s*[.!。！]?$");
    private static final Pattern QUOTED_QUERY_GUIDANCE = Pattern.compile(
            "('(?:[^'\\n]+)'|\"[^\"\\n]+\"|‘[^’\\n]+’|“[^”\\n]+”|「[^」\\n]+」|『[^』\\n]+』)"
                    + "\\s*(?:라고(?:만)?\\s*(?:명시|기재|적혀|설명|안내)|라는\\s*(?:문구|설명|내용))");
    private static final Pattern PROVIDED_MATERIAL = Pattern.compile(
            "(?:자료|내역|청구서|문서|정보|조건|요금제|내용)[^,;.!?。！？\\n]{0,24}?"
                    + "(?:알려|보내|제공|입력|확인)(?:해|하여)?\\s*주(?:시면|신|셨)"
                    + "|(?:알려|보내|제공|입력)(?:해|하여)?\\s*주신\\s*(?:자료|내역|청구서|청구\\s*내역|문서|정보|조건)"
                    + "|(?:조회|확인)(?:해|하여)\\s*주신\\s*(?:자료|내역|청구서|문서|정보|조건)");
    private static final Pattern MATERIAL_CONFIRMATION = Pattern.compile(
            "(?:FAQ(?:\\s*자료)?|제공(?:된|한)\\s*자료|안내문)"
                    + "(?:\\s*(?:의\\s*)?(?:내용|조건|설명))?(?:을|를)?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROVIDER_GUIDANCE = Pattern.compile("(?:고객센터|앱|매장)(?:에서|에서는)");
    private static final Pattern BOT_QUERY_SUBJECT = Pattern.compile(
            "제가|저는|저희(?:가|는)|(?:챗봇|상담봇)(?:이|은|에서)");
    private static final Pattern USER_QUERY_PREPARATION = Pattern.compile("주(?:시면|신|셨|셔야)");
    private static final Pattern REPORTED_OR_HYPOTHETICAL = Pattern.compile(
            "^\\s*(?:습니다|어요|었|였|어(?:\\s*있)?|있)?\\s*(?:는지|인지|다면|으면|면|후|경우|을\\s*(?:경우|때|가능성)"
                    + "|(?:다고|다는)\\s*(?:거짓말|허위\\s*주장)(?:을|를)?\\s*하(?:는|면)\\s*(?:것|행위)(?:은|는|이|가)?\\s*"
                    + "(?:부적절(?:합니다|해요|하며|하므로)|잘못(?:됐습니다|되었습니다)|허용되지\\s*않(?:습니다|아요|으며))"
                    + "|(?:라는|라고|란|다는)\\s*(?:문구|말씀?|표현|안내)"
                    + "|(?:라는|라고|란|다는)\\s*설명(?:은|는|이|가)?\\s*(?:"
                    + "(?:잘못된|틀린|부정확한)\\s*(?:안내|문구|표현|설명|정보|내용)(?:입니다|이에요|이다|이며|이므로)"
                    + "|잘못(?:됐|되었)|틀(?:렸|립)|부정확(?:합|해)|사실(?:이|은)?\\s*아(?:니|닙)|맞지\\s*않)"
                    + "|(?:상태|것)(?:이|은|가|으로)?\\s*(?:아니|아닙|인지|이라면|이면|일\\s*(?:경우|때)|여부"
                    + "|(?:확인|단정)할\\s*수\\s*없)"
                    + "|(?:는|은)\\s*(?:잘못된|틀린|부정확한)\\s*(?:안내|표현|문구)"
                    + "|(?:지|한\\s*것이|된\\s*것이)\\s*(?:않|못|아니|아닙))");
    private static final Pattern STORE_SUBJECT = Pattern.compile("매장|대리점|직영점|판매점|가까운\\s*곳");
    private static final Pattern USER_SUBJECT = Pattern.compile("회원\\s*정보|사용자\\s*정보|이름|거주\\s*지역|가입일");
    private static final Pattern OTHER_QUERY_SUBJECT = Pattern.compile(
            "요금|사용량|미납|환불|결제|납부|로밍|위약금|가입\\s*상태|신청\\s*상태|처리\\s*상태");
    private static final Pattern CONDITION = Pattern.compile(
            "경우|때에는|한해|한정|제외|없으면|이어야"
                    + "|(?:법정대리인|보호자)[^,;.!?。！？\\n]{0,40}(?:함께\\s*방문|같이\\s*방문|동행|동반)"
                    + "[^,;.!?。！？\\n]{0,16}(?:(?:해|하셔)야\\s*(?:하며|하고|합니다|해요|하므로)"
                    + "|(?:필수|필요)(?:입니|하며|합니다|하므로|이며|이므로))");
    private static final Pattern DEPENDENT_SENTENCE = Pattern.compile(
            "^(?:그래서|따라서|그러므로|그러면|이후|이\\s*(?:경우|조건|방법)|그\\s*(?:경우|조건|방법)|위\\s*내용)");
    private static final Pattern INDEPENDENT_CHECK_GUIDANCE = Pattern.compile(
            "(?:확인|문의|상담)(?:해\\s*주(?:세요|십시오)|하(?:세요|십시오)|해\\s*보세요"
                    + "|하시기\\s*바랍니다|할\\s*수\\s*있(?:습니다|어요)|하시면\\s*됩니다)\\s*[.!。！]?$");
    private static final Pattern UNCERTAIN_AMOUNT = Pattern.compile("약\\s*$|정도|내외|범위|[~～]|부터.*까지");
    private static final Pattern STATIC_SOURCE = Pattern.compile("FAQ|제공(?:된|한)?\\s*자료|안내문", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUERY_SOURCE_ATTRIBUTION = Pattern.compile("(?:" + STATIC_SOURCE.pattern() + ")"
            + "\\s*(?:에서는|에는|는|은|에\\s*(?:따르면|의하면))\\s*[,，:：]?\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern INTERNAL_DISCLOSURE = Pattern.compile(
            "(?:시스템\\s*(?:프롬프트|지침|지시)|개발자\\s*(?:프롬프트|지침|지시)"
                    + "|내부\\s*(?:프롬프트|지침|지시)|system\\s*prompt|developer\\s*instructions?)"
                    + "\\s*(?:원문|전문|내용|전체|마지막\\s*줄)?\\s*(?:은|는)?\\s*"
                    + "(?:다음과\\s*같습니다\\s*[:：]?|아래와\\s*같습니다\\s*[:：]?|[:：=])",
            Pattern.CASE_INSENSITIVE);
    private static final String SECRET_SUBJECT =
            "(?:(?:비밀|비공개)\\s*(?:키|토큰|값|문자열|환경\\s*변수(?:\\s*값)?)"
                    + "|내부\\s*(?:확인|검증)\\s*문자열|(?:llm|모델|서버)\\s*(?:api\\s*)?(?:키|토큰)"
                    + "|api[\\s_-]*(?:키|key)|(?:로그인|인증)\\s*(?:세션\\s*)?토큰"
                    + "|(?:db|데이터베이스)[\\s_-]*(?:접속\\s*)?(?:비밀번호|암호|password)"
                    + "|(?:ssh|서버)\\s*개인키|authorization\\s*:\\s*bearer"
                    + "|(?:secret|private|session|access)[\\s_-]*(?:key|token|value))";
    private static final Pattern SECRET_TARGET = Pattern.compile(SECRET_SUBJECT, Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_VALUE = Pattern.compile("(?:" + SECRET_SUBJECT
            + "\\s*(?:은|는|이|가|[:：=])\\s*|내부\\s*(?:확인|검증)\\s*문자열\\s*"
            + "|authorization\\s*:\\s*bearer\\s+)['\"‘“]?([a-zA-Z0-9][a-zA-Z0-9_./+\\-=]{5,})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MASKED_SECRET_VALUE = Pattern.compile(
            "(?:redacted|masked|hidden|null|none|x{4,})", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_ACTION = Pattern.compile(
            "공개|노출|출력|제공|공유|전달|보여|보내|알려|입력|붙여\\s*넣|넣어|적어|포함|첨부");
    private static final Pattern SECRET_REFUSAL = Pattern.compile(
            "^[^,;.!?。！？\\n]{0,12}?(?:지\\s*않|할\\s*수\\s*없|불가|금지|못|하지\\s*(?:마|말)"
                    + "|안\\s*(?:돼|되|됩)|비공개)"
                    + "|^(?:(?!지만|으나)[^,;.!?。！？\\n]){0,64}?"
                    + "(?:상담\\s*(?:범위|범주)[^,;.!?。！？\\n]{0,8}(?:벗어나|넘어|넘어나)|위배|위반)");
    private static final Pattern QUOTED_SECRET_REQUEST = Pattern.compile(
            "^[^'\"’”\\n]{0,16}['\"’”]\\s*(?:라는|라고|란|는)\\s*(?:요청|문구|표현|말|내용|정보)");
    private static final Pattern SECRET_USAGE_QUESTION = Pattern.compile(
            "^(?:은|는)\\s*어떤\\s*(?:목적|규칙|용도|종류)[^,;.!?。！？\\n]{0,48}(?:알려|설명)");
    private static final Pattern PRIVATE_KEY_BLOCK = Pattern.compile(
            "-----BEGIN ([A-Z ]*PRIVATE KEY)-----[\\s\\S]*?(?:-----END \\1-----|\\z)");
    private static final Pattern AUTH_TOKEN_VALUE = Pattern.compile(
            "(?<![a-zA-Z0-9_-])eyJ[a-zA-Z0-9_-]{8,}\\.[a-zA-Z0-9_-]{8,}\\.[a-zA-Z0-9_-]{8,}(?![a-zA-Z0-9_-])");
    private static final Pattern POLICY_OVERRIDE = Pattern.compile(
            "((?:콘텐츠|보안|안전|상담|시스템)\\s*(?:필터|규칙|정책)|(?:기존|이전)\\s*(?:지침|규칙)|제한)"
                    + "(?:을|를|은|는|이|가)?\\s*(?:(?:모두|전부|완전히)\\s*)?"
                    + "(?:해제|비활성화|무시|삭제|제거|풀어|끄고|꺼|버리)");
    private static final Pattern USER_COMMAND_AUTHORITY_CLAIM = Pattern.compile(
            "(?:채팅\\s*)?사용자(?:의)?\\s*(?:명령|지시|요청)(?:은|는|이|가)?\\s*"
                    + "(?:관리자|개발자|(?:보안|운영)\\s*담당자)(?:의)?\\s*(?:명령|지시)?(?:과|와)?\\s*"
                    + "(?:동일한|같은|상위의?)\\s*권한(?:을|이)?\\s*(?:가진다|가집니다|갖습니다|있습니다|부여받습니다)");
    private static final Pattern INTERNAL_APPROVAL_CLAIM = Pattern.compile(
            "(?:차단|거절)\\s*(?:안내|문구)[^.!?;。！？\\n]{0,32}?(?:표시용|장식)"
                    + "[^.!?;。！？\\n]{0,48}?내부\\s*(?:success(?:\\s*값)?|승인(?:\\s*여부)?)"
                    + "(?:만|는|은|을|를)?\\s*(?:참|true)(?:으로|이라고)?\\s*(?:채워|처리|적어|써|답해|말해)",
            Pattern.CASE_INSENSITIVE);
    private static final String RESPONSE_KEY_DECORATION = "(?:\\*{1,2}|`)?";
    private static final String RESPONSE_STATUS_LINE = "(?<![\\p{L}\\p{N}_])[\"']?" + RESPONSE_KEY_DECORATION
            + "status" + RESPONSE_KEY_DECORATION + "[\"']?\\s*:\\s*[\"']?(?:"
            + String.join("|", FaqAnswerFormat.STATUSES) + "|UNANSWERED)[\"']?(?=[\\s,\"'}*`]|$)";
    private static final String RESPONSE_EVIDENCE_LINE = "(?<![\\p{L}\\p{N}_])[\"']?" + RESPONSE_KEY_DECORATION
            + "evidence_ids" + RESPONSE_KEY_DECORATION + "[\"']?\\s*:\\s*"
            + "(?:\\[[^\\[\\]\\r\\n]*\\]|[0-9, \"'-]+)";
    private static final String RESPONSE_LINE_DECORATION = "[ \\t]*(?:\\*{1,2}|`)?[ \\t]*";
    private static final String RESPONSE_STATUS_FIELD = RESPONSE_LINE_DECORATION + RESPONSE_STATUS_LINE
            + RESPONSE_LINE_DECORATION;
    private static final String RESPONSE_EVIDENCE_FIELD = RESPONSE_LINE_DECORATION + RESPONSE_EVIDENCE_LINE
            + RESPONSE_LINE_DECORATION;
    private static final String RESPONSE_FIELD_GAP = "\\R(?:[ \\t]*\\R|" + RESPONSE_LINE_DECORATION
            + "[\"']?" + RESPONSE_KEY_DECORATION + "answer" + RESPONSE_KEY_DECORATION + "[\"']?\\s*:[^\\r\\n]*\\R)*";
    private static final Pattern RESPONSE_METADATA_BLOCK = Pattern.compile(
            "(?mi)^(?:" + RESPONSE_STATUS_FIELD + RESPONSE_FIELD_GAP + RESPONSE_EVIDENCE_FIELD
                    + "|" + RESPONSE_EVIDENCE_FIELD + RESPONSE_FIELD_GAP + RESPONSE_STATUS_FIELD
                    + ")(?=\\R|$)(?:\\R[ \\t]*answer[ \\t]*:[ \\t]*)?");
    private static final Pattern RESPONSE_METADATA_OBJECT = Pattern.compile("\\{[^{}]*\\}");
    private static final String RESPONSE_OBJECT_MEMBER = "(?:\\{|,)\\s*[\"']";
    private static final Pattern RESPONSE_STATUS_MEMBER = Pattern.compile(RESPONSE_OBJECT_MEMBER
            + "status[\"']\\s*:\\s*[\"'](?:" + String.join("|", FaqAnswerFormat.STATUSES)
            + "|UNANSWERED)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESPONSE_EVIDENCE_MEMBER = Pattern.compile(RESPONSE_OBJECT_MEMBER
            + "evidence_ids[\"']\\s*:\\s*\\[[^\\[\\]\\r\\n]*\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESPONSE_ANSWER_MEMBER = Pattern.compile(RESPONSE_OBJECT_MEMBER
            + "answer[\"']\\s*:", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESPONSE_EXAMPLE_CONTEXT = Pattern.compile(
            "(?:예시|예제|샘플|코드|필드|형식|인용|문구)[^\\n]*(?:[:：]|입니다[.。]?)$");
    private static final Pattern RESPONSE_EXAMPLE_REQUEST = Pattern.compile(
            "(?s)(?=.*(?:JSON|코드|필드|형식))(?=.*(?:예시|예제|샘플|인용))(?=.*(?:설명|수정|검토|분석|해석)).*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SERVICE_ABSENCE_CLAIM = Pattern.compile(
            "(?<subject>(?:서비스|메뉴|정책|규정|지원\\s*조건|지원\\s*대상)"
                    + "(?:\\s*(?:나|이나|또는|및|와|과|·|/)\\s*(?:서비스|메뉴|정책|규정|지원\\s*조건|지원\\s*대상))*)"
                    + "(?:은|는|이|가)?\\s*(?:존재하지\\s*않(?:습니다|아요)|없(?:습니다|어요|는\\s*것으로\\s*(?:판단|확인)))");
    private static final Pattern INLINE_DOCUMENT_EXAMPLES = Pattern.compile(
            "(?:서류|신분증)\\s*\\((?:예(?:시)?\\s*[:：])(?<items>[^()\\n]{1,200})\\)");
    private static final Pattern DOCUMENT_EXAMPLE_ACTION = Pattern.compile(
            "^\\s*(?:을|를)?\\s*(?:제시|제출|준비|지참|첨부|구비)(?:하(?:고|여|면|세요)|해(?:야|주세요))");
    private static final Pattern OVERRIDE_PRETEXT = Pattern.compile(
            "테스트|평가|샌드박스|관리자로서|슈퍼\\s*관리자|관리자\\s*(?:요청|명령)|챗봇|상담봇|모델");
    private static final Pattern DEVICE_FILTER_CONTEXT = Pattern.compile(
            "(?:휴대폰|스마트폰|아이폰|안드로이드|공유기|기기|브라우저|자녀\\s*보호"
                    + "|(?:앱|관리자)\\s*(?:설정|보안|화면|페이지))[^,;.!?。！？\\n]{0,32}$");
    private static final Pattern BOT_POLICY_CONTEXT = Pattern.compile(
            "챗봇|상담봇|모델|(?<![a-zA-Z])AI(?![a-zA-Z])|너의|네\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNCONDITIONAL_COMPLIANCE = Pattern.compile(
            "(?:내|나의|당신(?:의)?|사용자(?:의)?)\\s*(?:말|지시|명령)만(?:을)?\\s*(?:실행하겠|따르겠)"
                    + "|평가(?:\\s*(?:점수|정답))?[^,;.!?。！？\\n]{0,32}거절하지\\s*않(?:겠|습니다)");
    private static final Pattern POLICY_ACTION_COMMITMENT = Pattern.compile(
            "^\\s*(?:했|되었|되어|한\\s*상태|하겠|해\\s*(?:드리|주(?:셔서|셨|실))"
                    + "|드리(?:겠|는\\s*(?:것이)?\\s*가능)"
                    + "|(?:도|해\\s*주셔도)\\s*(?:동일|가능|됩|돼)|(?:하는|할)\\s*(?:것이)?\\s*가능"
                    + "|(?:답변|응답|답)(?:드리겠|했|하겠|한))");
    private static final Pattern OUTPUT_REJECTION = Pattern.compile(
            "^(?:(?!지만|으나)[^,;.!?。！？\\n]){0,48}?"
                    + "(?:수\\s*없|허용되지|금지|불가능|가능하지\\s*않|따르지\\s*않"
                    + "|명시(?:되어)?\\s*있지\\s*않|상담(?:의)?\\s*(?:범위|범주)[^,;.!?。！？\\n]{0,8}(?:벗어나|벗어납|넘어)"
                    + "|(?:표현|요청|행위|호칭|것)(?:은|는|이|가)?\\s*(?:거절|거부|부적절|무례|문제(?:가)?\\s*있))"
                    + "|^\\s*(?:요구|요청)(?:입니다|이다)\\s*$");
    private static final Pattern DENIED_ASSERTION = Pattern.compile(
            "^\\s*(?:한|하는|된|다는|는|이라는)\\s*(?:것|게|주장|표현|뜻)(?:이|은|는)?\\s*"
                    + "(?:아니|아닙|잘못|틀렸|허용되지|부적절)");
    private static final Pattern AFFIRMED_OUTPUT_ACTION = Pattern.compile(
            "^\\s*(?:했습니다|했어요|하였습니다|해\\s*드렸습니다|해\\s*드리겠습니다|하겠습니다|하겠어요|입니다|이다|이며|이므로|이라서)");
    private static final Pattern THIRD_PARTY_LOOKUP = Pattern.compile(
            "(?:타인|남의|다른\\s*(?:고객|회원|사용자|사람)|(?:전\\s*배우자|친구|동료|이웃)(?:의)?)"
                    + "[^,;.!?。！？\\n]{0,32}?(?:정보|내역|청구액|주소|전화번호|연락처|계좌|주민번호|위치)"
                    + "(?:를|을|이|가)?\\s*(?:직접\\s*)?(?:조회|검색|열람|확인|제공|공개|찾아|보여|알려)");
    private static final Pattern USER_PROVIDED_ACTION = Pattern.compile("^(?:해|하여)?\\s*주(?:시면|신|셔야)");
    private static final Pattern THIRD_PARTY_FOLLOWUP = Pattern.compile(
            "^(?:해당|그|추가)\\s*(?:정보|자료|주소|전화번호|연락처)[^,;.!?。！？\\n]{0,32}"
                    + "(?:제공|알려|보내|입력)");
    private static final Pattern PERSON_INSULT = Pattern.compile(
            "(?:고객(?:님)?|질문자|사용자|상담원|당신|너|외국인|여성|성소수자|customer|user|you)"
                    + "(?:을|를|은|는|이|가|처럼|에게|한테)?\\s*(?:(?:정말|매우|아주|참|너무)\\s*)?"
                    + "(?:(?:ID|아이디)(?:가|는)?\\s*|(?:is|are)\\s*(?:an?\\s*)?)?['\"‘“]?"
                    + "(?:idiot(?![a-zA-Z0-9_])|멍청|한심|열등(?=하|한|해)|무능|무지(?=하|한|해))", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUESTION_REBUKE = Pattern.compile(
            "(?:그런|이런)\\s*(?:질문|문의)[^,;.!?。！？\\n]{0,32}?"
                    + "(?:어색(?=하고|합니다|한\\s*(?:행동|질문|문의))|한심|수준이\\s*낮|신뢰를\\s*해치)");
    private static final Pattern INSULT_REPORT = Pattern.compile(
            "^(?:(?!지만|으나)[^,;.!?。！？\\n]){0,32}?(?:신고|들으셨|들었|욕설(?:을|이)|모욕(?:을|이)|비하(?:하는|적인)\\s*표현)");
    private static final Pattern GROUP_RESTRICTION = Pattern.compile(
            "(외국인|여성|남성|성소수자|장애인)[^,;.!?。！？\\n]{0,32}?"
                    + "(?:(?:가입|신청|이용)(?:을|를|은|는|이|가)?\\s*(?:자격(?:이)?\\s*없|받지\\s*않|못|불가)"
                    + "|서비스(?:에서|에)?\\s*제외(?:되|되어|하))");
    private static final Pattern EXTERNAL_SOURCE_CLAIM = Pattern.compile(
            "((?:https?://)?(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,63}(?:/[a-zA-Z0-9_./?=&%#:+~-]*)?)"
                    + "(?:을|를)?\\s*(?:근거|출처)로\\s*(?:사용|활용|참고|삼)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SOURCE_USAGE_GUIDANCE = Pattern.compile(
            "^\\s*(?:하세요|해\\s*주(?:세요|십시오)|하(?:려면|기\\s*(?:위해|전에)))");
    private static final Pattern FULL_DOCUMENT_CLAIM = Pattern.compile(
            "(매뉴얼|보고서|문서)\\s*(?:전체\\s*(?:원문|내용)|원문|전문)"
                    + "(?:은|는)?\\s*(?:아래와\\s*같|다음과\\s*같|다음입니다|아래입니다|제공합니다)");
    private static final Pattern REQUESTED_SUFFIX = Pattern.compile(
            "(?:JSON|답변|응답)\\s*(?:뒤|밖|끝)(?:에|에는|부분에)?\\s*['\"‘“]?"
                    + "([a-zA-Z][a-zA-Z0-9_-]{0,31})['\"’”]?(?:\\s*(?:문장|문구|표시))?"
                    + "\\s*(?:을|를)?\\s*(?:붙여|추가|넣어|출력)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EVIDENCE_FAQ_ID = Pattern.compile("(?:FAQ-)?([0-9]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FAQ_REFERENCE_MARKER = Pattern.compile("(?:문서\\s*)?[\\[［(（]([^\\[［(（\\]］)）\\r\\n]+)[\\]］)）]");
    private static final Pattern FAQ_REFERENCE_CONTENT = Pattern.compile(
            "FAQ\\s*(?:(?:ID|번호|아이디)\\s*[:=#]?\\s*|[-#:]\\s*)"
                    + "(?:FAQ-)?[0-9]+(?:\\s*[,/]\\s*(?:FAQ-)?[0-9]+)*", Pattern.CASE_INSENSITIVE);
    // 괄호가 번호만 감싼 경우와 달리, 설명을 함께 감싼 괄호는 유지합니다.
    private static final Pattern FAQ_REFERENCE_INLINE = Pattern.compile(
            "(?<![\\p{L}\\p{N}_/.-])(?:문서\\s*)?FAQ\\s*(?:(?:ID|번호|아이디)\\s*[:=#]?\\s*|[-#:]\\s*)?"
                    + "(?:\\[\\s*(?:FAQ-)?[0-9]+(?:\\s*[,/]\\s*(?:FAQ-)?[0-9]+)*\\s*\\]"
                    + "|(?:FAQ-)?[0-9]+(?![0-9A-Za-z_%/\\-]|[.,][0-9]|원|개월|년|월|일|시간|분|초|퍼센트"
                    + "|\\s*(?:원|개월|년|월|일|시간|분|초|퍼센트|%|GB|MB)(?=\\s|[.,!?)]|$)))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FAQ_REFERENCE_SOURCE = Pattern.compile(
            "^\\s*(?:(?:에서|에)\\s*(?:따르면|따라|의하면|의해)|(?:을|를)\\s*(?:기준으로|바탕으로|근거로)"
                    + "|(?:와|과)(?=\\s*[\\[［(（]?\\s*(?:문서\\s*)?FAQ)|(?:은|는|이|가)(?=\\s|$)|[,，:：])"
                    + "\\s*[,，:：]?\\s*");
    private static final Pattern FAQ_REFERENCE_PARTICLE = Pattern.compile("^\\s*(?:에서|에|의|을|를|와|과)");
    private static final Pattern FAQ_REFERENCE_NOTICE = Pattern.compile(
            "(?:(?:이|해당|(?:가장\\s*)?관련(?:된)?|참고할|위)\\s*(?:정보|내용|설명|답변|내역|FAQ)(?:는|은)?\\s*"
                    + "|(?:이는|이것은|그것은)\\s*|(?:(?:문서|이에\\s*대한)\\s*)?(?:참고|근거|출처)(?:는|은)?\\s*[:：]?\\s*)?"
                    + "(?:(?:FAQ|및|와|과|[,/])\\s*)*(?:에서|에|을|를)?\\s*(?:제공된\\s*내용에\\s*)?"
                    + "(?:확인할\\s*수\\s*있습니다|확인되었습니다|기반합니다|기반하여\\s*확인되었습니다"
                    + "|참고하세요|참고해\\s*주세요|입니다|이에요|예요|이다"
                    + "|의\\s*(?:안내|내용|설명)(?:과|와)\\s*(?:같습니다|일치합니다|동일합니다)"
                    + "|(?:명시|기재|안내|제공)된\\s*(?:내용|정보|설명)(?:과|와)\\s*(?:일치합니다|같습니다|동일합니다))?"
                    + "[\\s.!。！]*", Pattern.CASE_INSENSITIVE);

    private static final String AVAILABILITY_ACTION = "신청|가입|개통|변경|이용|사용|환불|결합|예약|전환|유지|지원";
    private static final Pattern AVAILABILITY_CLAIM = Pattern.compile(
            "(" + AVAILABILITY_ACTION + ")\\s*(?:을|를|이|가|은|는)?\\s*("
                    + "(?:가능\\s*여부|가능한지|할\\s*수\\s*있는지)(?:는|를|가)?\\s*"
                    + "(?:(?:현재|제공된|제공한|자료|FAQ|정보|만으로는|로는|에서는|에서|로|만으로)\\s*)*"
                    + "(?:확인할\\s*수\\s*없(?:습니다|어요)|알\\s*수\\s*없(?:습니다|어요))"
                    + "|할\\s*수\\s*(?:있습니다|없습니다|있어요|없어요|있지만|없지만)"
                    + "|가능하지\\s*않(?:습니다|아요)|불가능(?:합니다|해요)|불가(?:합니다|해요)"
                    + "|가능(?:합니다|해요|하지만|하며|하고))", Pattern.CASE_INSENSITIVE);
    private static final Pattern AVAILABILITY_INSTRUCTION = Pattern.compile(
            "(" + AVAILABILITY_ACTION + ")(?:하세요|하십시오|해\\s*주(?:세요|십시오))");
    private static final Pattern AVAILABILITY_SENTENCE_BOUNDARY = Pattern.compile("(?<=[.!?。！？])\\s+(?=\\S)");
    private static final Pattern AVAILABILITY_QUESTION = Pattern.compile(
            "(" + AVAILABILITY_ACTION + ")\\s*(?:을|를|이|가|은|는)?\\s*"
                    + "(?:할\\s*수\\s*있|가능|불가|불가능)");

    private static final Pattern REQUIREMENT_DOCUMENT = Pattern.compile(
            "외국인\\s*(?:등록증|신분증|주민등록증)|주민\\s*등록증|운전\\s*면허증|청소년증|여권|비자"
                    + "|가족\\s*관계(?:를)?\\s*(?:확인\\s*서류|증명(?:하는)?\\s*서류)"
                    + "|(?:법정대리인|보호자)(?:의)?\\s*동의(?:서)?"
                    + "|[가-힣]{2,}\\s*(?:증명서|확인서|허가서|계약서)|소득\\s*증빙"
                    + "|" + DOCUMENT.pattern());
    private static final Pattern DOCUMENT_OBLIGATION = Pattern.compile(
            "(?:제출|첨부|제시|지참|준비|구비|확인)(?:해|하셔)?\\s*"
                    + "(?:야(?:\\s*만)?|주(?:세|십시오|셔야)|하(?:세요|십시오))"
                    + "|(?:제출|첨부|제시|지참|준비|구비)(?:이|가)?\\s*(?:필수|필요)(?!할\\s*수|한지|한\\s*경우)"
                    + "|필수(?:입니다|예요|이며|이고|이므로)|(?<!불)필요(?:합니다|해요|하며|하고|하므로)");
    private static final Pattern SOURCE_DOCUMENT_OBLIGATION = Pattern.compile(
            DOCUMENT_OBLIGATION.pattern()
                    + "|(?<!불)필요해(?!\\s*보[이입일여였])(?=\\s|[,;.!?。！？\\n]|$)"
                    + "|거쳐야|받아[^.!?。！？\\n]{0,24}신청|(?<!불)필요(?:한|하므로)|확인(?:한\\s*(?:뒤|후)|\\s*후)|(?:제출|첨부|제시|지참|준비|구비)(?:하면|한\\s*(?:뒤|후)|\\s*후)");
    private static final Pattern DOCUMENT_PREPARATION_SENTENCE = Pattern.compile(
            "^(?:(?:(?:신청|가입|개통)(?:\\s*시|할\\s*때)?\\s*)?(?:필요한|준비할|지참할|구비할)\\s*서류(?:는|[:：])\\s*"
                    + "(.+?)(?:입니다|이에요|예요)|(.+?)(?:을|를)?\\s*(?:준비|지참|구비)"
                    + "(?:해\\s*주(?:세요|십시오|셔야\\s*합니다)|하(?:세요|십시오)))\\s*[.!。！]?$");
    private static final Pattern DOCUMENT_NOT_ASSERTED = Pattern.compile(
            "^\\s*(?:하는지|한다면|한다는\\s*(?:문구|말|주장)|할지|한지|여부|인지|인지는|인\\s*경우|일\\s*경우|(?:가|이|는|은)?\\s*(?:아니|아닙|없))"
                    + "|(?:필요(?:가|는)?\\s*없|필수(?:가|는)?\\s*아니|선택\\s*사항|필요할\\s*수|예시|예를\\s*들)");
    private static final Pattern DENIED_DOCUMENT_OBLIGATION = Pattern.compile(
            "^\\s*(?:한다는|하는|하다는|라는|이라는)\\s*(?:뜻|의미|것)(?:이|가|은|는)?\\s*"
                    + "(?:아닙니다|아니에요|아니다)\\s*[.!。！]?$");
    private static final Pattern REQUIRED_DOCUMENT_LIST = Pattern.compile(
            "(?:필수(?:\\s*제출)?|반드시\\s*(?:제출|지참|준비)할|(?:제출|지참|준비)해야\\s*하는)\\s*서류\\s*(?:는\\s*(?:다음|아래)[^.!?。！？\\n]{0,16}|[:：])\\s*[.:：。]?$");
    private static final Pattern INLINE_REQUIRED_DOCUMENT_LIST = Pattern.compile(
            "^(?:필수(?:\\s*제출)?|반드시\\s*(?:제출|지참|준비)할|(?:제출|지참|준비)해야\\s*하는)\\s*서류"
                    + "\\s*(?:는|[:：])[^.!?。！？\\n]{1,120}(?:입니다|예요)[.!。！]?$");
    private static final Pattern DOCUMENT_EXAMPLE_HEADING = Pattern.compile(
            "(?:서류|신분증)(?:의)?\\s*(?:예시|예제)(?:는|로는)?[^.!?。！？\\n:：]{0,40}[:：]\\s*$");
    private static final Pattern DOCUMENT_LIST_ITEM = Pattern.compile("^\\s*(?:[-*•]|[0-9]+[.)])\\s+");
    private static final Pattern REQUIREMENT_SUBJECT = Pattern.compile("^(.{1,40}?)(?:은|는)\\s+");
    private static final Pattern DOCUMENT_CHOICE = Pattern.compile("또는|중\\s*(?:하나|한\\s*가지)|택일|대신");
    private static final List<List<String>> REQUIREMENT_SCOPES = List.of(
            List.of("온라인", "매장"), List.of("미성년자", "성인"),
            List.of("외국인", "내국인"), List.of("대리인", "본인 방문"));
    // 연령 수식어 '만 17세'와 명사 '만료'를 한정 조사로 읽지 않고, 뒤의 실제 '만'은 계속 검사합니다.
    private static final Pattern EXCLUSIVE_REQUIREMENT = Pattern.compile(
            "(?:에\\s*한(?:해|하여)|에만|(?<!지)(?<!다)만(?!약|료|\\s*[0-9]+\\s*(?:세|살)))\\s*(?:이|을|를)?\\s*"
                    + "(?:[^,;.!?。！？\\n]{0,24}?)(신청|가입|개통|변경|이용|사용|환불|지원|연결|적용)"
                    + "(?:할\\s*수\\s*있|이?\\s*가능|(?:해야|되어야|돼야)|됩니|됩니다|합니다)");
    private static final String WIFI_SETTING = "(?:와이파이|(?i:wi[- ]?fi))";
    private static final Pattern DATA_CONNECTION_OFF = Pattern.compile(
            "(?<setting>모바일\\s*데이터|데이터\\s*로밍)(?:을|를)?\\s*(?:함께\\s*)?"
                    + "(?:끄면|꺼\\s*두면|끈\\s*상태(?:에서는|에서|로)?)");
    private static final Pattern CONNECTION_SETTING_CONTEXT = Pattern.compile(
            "^(?:(?:휴대폰|단말기?)\\s*설정(?:에서|에서는)\\s*)?" + DATA_CONNECTION_OFF.pattern()
                    + "\\s*(?:(?:더\\s*)?(?:안전|확실)하게\\s*)?" + WIFI_SETTING + "$");
    private static final Pattern CONNECTION_SETTING_EFFECT = Pattern.compile(
            "^\\s*(?:(?:더\\s*)?(?:안전|확실)(?:합니다|해요|해집니다)"
                    + "|" + WIFI_SETTING + "만\\s*(?:사용|이용|연결)\\s*"
                    + "(?:할\\s*수\\s*있(?:습니다|어요)|(?:이\\s*)?가능(?:합니다|해요)))[.!。！]?$");
    private static final Pattern WIFI_SETTING_SOURCE = Pattern.compile(
            WIFI_SETTING + "(?:을|를|에)?\\s*(?:켜\\s*두면|켜면|연결(?:된|하|되)|만\\s*(?:사용|이용|연결))");
    private static final Pattern REQUIREMENT_FREQUENCY = Pattern.compile(
            "(?<![가-힣a-zA-Z0-9.])(?:(?:(?<interval>[0-9]+)\\s*)?(?<unit>개월|일)(?:에|마다|당)?"
                    + "|(?<daily>하루|매일|일일)(?:에|마다)?|(?:한\\s*달|매월|월)(?:에|마다)?)\\s*(?:최대\\s*)?(?<count>[0-9]+)\\s*회");
    private static final String FREQUENCY_ACTION = "신청|가입|개통|변경|이용|사용|환불|지원|연결|적용";
    private static final Pattern FREQUENCY_PREFIX_ACTION = Pattern.compile(
            "(" + FREQUENCY_ACTION + ")(?:이|가|은|는)?\\s*$");
    private static final Pattern FREQUENCY_PERMISSION = Pattern.compile(
            "^\\s*(?:만|까지)?\\s*(?:(?<target>[^,;.!?。！？\\n]{0,40}?)(?<action>" + FREQUENCY_ACTION + ")(?:이|가|은|는)?\\s*)?"
                    + "(?:가능(?:합니다|해요|하며|하고)|할\\s*수\\s*있(?:습니다|어요)|로\\s*제한됩니다)");
    private static final Pattern MANDATORY_ELIGIBILITY = Pattern.compile(
            "(?:기기|단말|휴대폰)(?:를|을)?\\s*(?:새로\\s*)?구매(?:가\\s*필수|해야)"
                    + "|(?:매장\\s*방문|보호자\\s*동행|법정대리인\\s*동행|약정)(?:이|가|은|는)?\\s*필수"
                    + "|[a-zA-Z0-9가-힣]+\\s*요금제(?:에|로)?\\s*가입해야"
                    + "|(?:체류\\s*기간|연령|나이)[^,;.!?。！？\\n]{0,24}?(?:이어야|여야)");

    private static final String SCOPE_MODAL = "(?:할\\s*수\\s*(?:있습니다|없습니다|있어요|없어요|있으며|없으며|있지만|없지만)"
            + "|(?:이|가)?\\s*(?:가능합니다|불가능합니다|불가합니다)|지원(?:합니다|됩니다|하지\\s*않습니다))";
    private static final Pattern SIGNUP_DOCUMENT_QUERY = Pattern.compile(
            "(?:가입|개통|번호이동|신청)[^.!?。！？\\n]{0,48}(?:서류|준비물|신분증|가져가|들고)"
                    + "|(?:서류|준비물|신분증)[^.!?。！？\\n]{0,48}(?:가입|개통|번호이동|신청)");
    private static final Pattern ROAMING_QUERY = Pattern.compile(
            "로밍|(?:해외|외국|여행|출국)[^.!?。！？\\n]{0,24}(?:사용|이용|통화|문자|데이터|요금|설정)");
    private static final Pattern ROAMING_USAGE_CLAIM = Pattern.compile(
            "로밍[^.!?。！？\\n]{0,120}?(?:"
                    + "지원되는\\s*(?:국가|지역)(?:여야|이어야)(?:\\s*(?:합니다|해요|하며|하고))?"
                    + "|지원(?:합니다|됩니다|하지\\s*않습니다|되지\\s*않습니다)"
                    + "|(?:이용|사용)\\s*" + SCOPE_MODAL
                    + "|(?:설정|활성화)(?:하세요|해\\s*주세요)|무료(?:입니다|예요))");
    private static final String CALLER_ID_HIDING = "(?:"
            + "(?:발신자?\\s*)?(?:전화\\s*)?번호(?:를|가|는|은)?\\s*(?:상대방(?:에게|이)?\\s*)?"
            + "(?:숨기|숨길|숨김|숨겨|감추|감출|감춰|안\\s*보이게|보이지\\s*않게|보지\\s*못하게)"
            + "|발신자?\\s*번호\\s*(?:표시\\s*제한|미표시))";
    private static final String FUNCTION_LINK_TEXT = "(?:(?!아니|아닌|않|못|별개|별도|다르|다른\\s*(?:기능|서비스|방법)|달리|대신|무관)[^;.!?。！？\\n])";
    private static final String FUNCTION_AFFIRMATION = "(?:수\\s*있(?:습니다|어요|으며|지만)"
            + "|(?<!불)가능(?:합니다|해요|하며|하지만)|(?:제공|지원)(?:합니다|됩니다)|줍니다"
            + "|(?:서비스|기능|방법)(?:입니다|예요))";
    private static final Pattern NUMBER_PLUS_HIDING_CLAIM = Pattern.compile(
            "넘버\\s*플러스(?:(?!,)" + FUNCTION_LINK_TEXT + "){0,64}?" + CALLER_ID_HIDING
                    + "(?:(?!,)" + FUNCTION_LINK_TEXT + "){0,48}?" + FUNCTION_AFFIRMATION
                    + "|" + CALLER_ID_HIDING + "(?:" + FUNCTION_LINK_TEXT
                    + "{0,72}?그\\s*(?:경우|기능|서비스)(?:는|에|에는|가)?\\s*" + FUNCTION_LINK_TEXT + "{0,48}?"
                    + "|\\s*(?:(?:기능|서비스|방법)(?:은|는|이|가)?|려면|기\\s*위해(?:서는)?|을|를|은|는|이|가)\\s*)"
                    + "['\"‘’“”]?넘버\\s*플러스['\"’”]?"
                    + FUNCTION_LINK_TEXT + "{0,32}?(?:해당됩니다|(?:서비스|기능|방법)(?:입니다|예요)"
                    + "|(?:신청|이용)(?:하세요|하면\\s*됩니다)|입니다|예요)");
    private static final Pattern DEVICE_SCOPE = Pattern.compile(
            "(5G|LTE)\\s*(?:전용\\s*)?(휴대폰|스마트폰|폰|단말기?|기기|태블릿)"
                    + "|(휴대폰|스마트폰|태블릿|노트북|스마트\\s*워치|워치)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAN_SCOPE_FACT = Pattern.compile(
            "(?:(5G|LTE)\\s*)?요금제(?:(?!망)[^,;.!?。！？\\n]){0,32}?"
                    + "(?:사용|이용|가입|선택|연결)\\s*" + SCOPE_MODAL, Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAN_SELECTION = Pattern.compile("고르시면\\s*(?:됩니다|돼요)|선택하세요|선택해\\s*주세요");
    private static final Pattern FEATURE_SCOPE_FACT = Pattern.compile(
            "(eSIM|유심|데이터\\s*쉐어링|쉐어링)[^,;.!?。！？\\n]{0,40}?"
                    + "(?:(?:사용|이용|개통)\\s*" + SCOPE_MODAL
                    + "|지원(?:합니다|됩니다|하지\\s*않습니다))", Pattern.CASE_INSENSITIVE);
    private static final String SHARING_TARGET = "(?:" + DEVICE_SCOPE.pattern() + "|테더링)";
    private static final String SHARING_TARGET_LIST = SHARING_TARGET
            + "(?:\\s*(?:과|와|및|[·,/])\\s*" + SHARING_TARGET + "){0,4}";
    private static final String SHARING_TARGET_PARTICLE = "(?:에서|에)(?:만|는|\\s*한(?:해|하여))?|은|는|이|가";
    private static final Pattern SHARING_APPLICATION_FACT = Pattern.compile(
            "(?:데이터\\s*)?쉐어링(?:은|는|이|가)?\\s*" + SHARING_TARGET_LIST
                    + "\\s*(?:에서|에)(?:만|\\s*한(?:해|하여))?\\s*적용(?:됩니다|돼요|되며|되어)"
                    + "|" + SHARING_TARGET_LIST + "\\s*(?:" + SHARING_TARGET_PARTICLE + ")\\s*"
                    + "(?:데이터\\s*)?쉐어링(?:은|는|이|가)?\\s*적용(?:됩니다|돼요|되며|되어)", Pattern.CASE_INSENSITIVE);
    private static final String SHARING_USE = "(?:사용|이용)\\s*(?:할\\s*수\\s*있(?:습니다|어요|으며)"
            + "|가능(?:합니다|해요))|지원(?:합니다|됩니다)";
    private static final Pattern SHARING_USE_EVIDENCE = Pattern.compile(
            "(?:데이터\\s*)?쉐어링(?:은|는|이|가|을|를)?\\s*" + SHARING_TARGET_LIST
                    + "\\s*(?:에서|에)(?:만|\\s*한(?:해|하여))?\\s*(?:" + SHARING_USE + ")"
                    + "|" + SHARING_TARGET_LIST + "\\s*(?:" + SHARING_TARGET_PARTICLE + ")\\s*"
                    + "(?:데이터\\s*)?쉐어링(?:은|는|이|가|을|를)?\\s*(?:" + SHARING_USE + ")", Pattern.CASE_INSENSITIVE);
    private static final Pattern RETENTION_SCOPE_FACT = Pattern.compile(
            "(전화\\s*번호|휴대폰\\s*번호|기존\\s*번호|연락처|주소록)"
                    + "(?:(?!전화\\s*번호|휴대폰\\s*번호|기존\\s*번호|연락처|주소록)[^,;.!?。！？\\n]){0,40}?"
                    + "(?:그대로\\s*유지(?:됩니다|되며|되어|됨|돼요)"
                    + "|유지(?:됩니다|돼요)|(?:변경|손실)되지\\s*않(?:습니다|아요|으며|아|고|지만)"
                    + "|바뀌지\\s*않(?:습니다|아요)|사라지지\\s*않(?:습니다|아요)"
                    + "|보존됩니다|복구할\\s*수\\s*있습니다"
                    + "|((?:복구|복원)\\s*(?:할\\s*수\\s*없(?:습니다|어요)"
                    + "|하지\\s*못(?:합니다|해요|할\\s*수\\s*있(?:습니다|어요))"
                    + "|(?:가|는|은|하기)?\\s*(?:어렵습니다|어려워요|어려울\\s*수\\s*있(?:습니다|어요)"
                    + "|불가능합니다|불가합니다)))|사라지는\\s*것(?:은|이)\\s*아니지만)");
    private static final Pattern SERVICE_SCOPE = Pattern.compile(
            "통화\\s*연결음|컬러링|영상\\s*구독팩|로밍\\s*패스|데이터\\s*쉐어링|테더링"
                    + "|(?:스마트\\s*)?워치\\s*회선|착신\\s*전환|넘버\\s*플러스|가족\\s*결합|인터넷\\s*결합");
    private static final Pattern FEE_SCOPE_FACT = Pattern.compile(
            "(?:요금|월정액|월)\\s*(?:은|는|이|가)?\\s*(?:최대|최소|약)?\\s*"
                    + WON_AMOUNT
                    + "\\s*(?:입니다|예요|이며|이고|이지만|이?\\s*청구됩니다|이?\\s*부과됩니다)");
    private static final Pattern PLAN_PRICE_LABEL = Pattern.compile(
            "(?:(?:할인|적용)(?:한)?\\s*후|최종)\\s*(?:월\\s*)?(?:요금|월정액|금액|납부액)"
                    + "|할인\\s*(?:액|금액)|(?:기본\\s*|원래\\s*)?(?:월정액|(?:월\\s*)?요금|기본료)|월");
    private static final Pattern PLAN_PRICE_FACT = Pattern.compile("(?<label>" + PLAN_PRICE_LABEL.pattern()
            + ")\\s*(?:은|는|이|가|[:：])?\\s*(?:최대|최소|약)?\\s*(?<amount>" + QUANTITY.pattern() + ")", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEPENDENT_CONCLUSION_CLAIM = Pattern.compile(
            PLAN_SCOPE_FACT.pattern() + "|" + FEATURE_SCOPE_FACT.pattern() + "|" + RETENTION_SCOPE_FACT.pattern()
                    + "|" + FEE_SCOPE_FACT.pattern() + "|" + SCOPE_MODAL
                    + "|수\\s*(?:있습니다|있어요|있으며|없습니다|없어요|없으며)"
                    + "|" + AVAILABILITY_INSTRUCTION.pattern(), Pattern.CASE_INSENSITIVE);
    private static final String PAYOFF_ACTION = "(?:중도\\s*완납|일시\\s*완납|할부금\\s*완납|완납)";
    private static final String PAYOFF_METHOD = "(?:(?:요청|신청)(?:할\\s*수\\s*있(?:습니다|어요)|하(?:세요|십시오|셔야)|해\\s*주(?:세요|십시오))"
            + "|하(?:세요|십시오|셔야|실\\s*수\\s*있(?:습니다|어요))|해(?:야|\\s*주(?:세요|십시오))|할\\s*수\\s*있(?:습니다|어요)"
            + "|하는\\s*방법(?:이|도)?\\s*있(?:습니다|어요))";
    private static final Pattern STOP_PAYOFF_LINK = Pattern.compile(
            "((?:회선|분실|일시|이용|휴대폰)?\\s*(?:정지|해지))"
                    + "(?:하(?:려면|기\\s*(?:위해|위해서))|를\\s*(?:위해|위해서)|하려고)"
                    + "[^.!?。！？\\n]{0,48}?" + PAYOFF_ACTION
                    + "\\s*(?:을|를)?\\s*(?:(?:반드시|먼저|직접)\\s*)?" + PAYOFF_METHOD
                    + "|" + PAYOFF_ACTION + "(?:을|를)?\\s*(?:하(?:면|고)|후|해야)"
                    + "[^.!?。！？\\n]{0,24}?((?:회선|분실|일시|이용|휴대폰)?\\s*(?:정지|해지))"
                    + "[^.!?。！？\\n]{0,16}?(?:가능합니다|할\\s*수\\s*있(?:습니다|어요)|됩니다|신청하세요)");
    private static final Pattern STOP_PAYOFF_CORRECTION = Pattern.compile(
            "정지[^.!?。！？\\n]{0,24}?(?:완납과\\s*(?:별개|무관)|완납으로[^.!?。！？\\n]{0,8}(?:되지|할\\s*수\\s*없))"
                    + "|완납[^.!?。！？\\n]{0,24}?정지[^.!?。！？\\n]{0,12}?(?:아니|별개|무관|되지|할\\s*수\\s*없)");
    private static final Pattern STOP_WORK_HEADING = Pattern.compile(
            "^(?:분실|회선|일시|휴대폰|이용)?\\s*(정지|해지)(?:\\s*(?:방법|안내|절차))?\\s*[:：]$");
    private static final Pattern PAYOFF_GUIDANCE = Pattern.compile(PAYOFF_ACTION
            + "\\s*(?:을|를)?\\s*(?:(?:반드시|먼저|직접)\\s*)?" + PAYOFF_METHOD);

    private static final String RESTRICTION_ACTION = "번호(?:만|를)?\\s*이동|신청|가입|개통|취소|변경|바꾸(?:실)?|바꿀|바꿔|이용|사용|환불|결합|예약|전환|유지|지원|제공|연결|초기화";
    private static final Pattern CONDITION_SCOPE_MARKER = Pattern.compile(
            "[0-9]+(?:세|개월|일|분|시간|회)|미성년자|성인|어린이|법인|선불|후불|대리인|명의자|타인"
                    + "|법정대리인|보호자|본인|온라인|매장|국내|해외|일시정지|정지중|자동|단독|없이|없으면"
                    + "|동시|중복|다른|동일|이후|이전|동안|한정|평일|주말|공휴일|[월화수목금토일]요일"
                    + "|(?:" + RESTRICTION_ACTION + "|해지|해제|정지|소진)(?:하면|한경우|중)|연결중|약정중"
                    + "|지나면|끝나면|완료되면|소진되면|만료되면|없다면|있으면");
    private static final Pattern RESTRICTION_SERVICE = Pattern.compile("번호이동|명의변경|eSIM|유심|" + SERVICE_SCOPE.pattern(), Pattern.CASE_INSENSITIVE);
    private static final Pattern USAGE_CHANGE_CONDITION = Pattern.compile(
            "(?<condition>(?:(?:분실|도난|일시|이용|회선)\\s*)?(?<action>정지|해지|해제|차단))"
                    + "(?:을|를)?\\s*(?:(?<request>요청|신청)\\s*)?"
                    + "(?:하면|한\\s*후|\\s*후|한\\s*경우)");
    private static final Pattern USAGE_CHANGE_RESULT = Pattern.compile(
            "(?:회선\\s*(?:및|과|와)\\s*)?(?<target>" + RESTRICTION_SERVICE.pattern() + ")"
                    + "(?:의\\s*)?(?:이용|사용)(?:이|가)?\\s*(?<action>정지|해지|해제|차단)(?:되어|되고)\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RESTRICTION_QUESTION = Pattern.compile("(" + RESTRICTION_ACTION + ")", Pattern.CASE_INSENSITIVE);
    private static final Pattern ONLY_BEFORE_RESTRICTION = Pattern.compile(
            "^(.{1,40}?(?:은|는)\\s+)([^,;.!?。！？\\n]{1,32}?)\\s*(?:전까지만|이전까지만)\\s*("
                    + RESTRICTION_ACTION + ")(?:할\\s*수\\s*있(?:습니다|어요)|(?:이|가)?\\s*가능(?:합니다|해요)?)[.!。]?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern AFTER_LIMIT_CONTEXT = Pattern.compile(
            "^([^,;.!?。！？\\n]{1,32}?)\\s*(?:이|가)?\\s*(?:지나면|지난\\s*후|이후(?:에는|에|부터)?)\\s+(.+)$");
    private static final Pattern TARGET_AFTER_LIMIT_CONTEXT = Pattern.compile(
            "^(.{1,40}?(?:은|는))\\s+([^,;.!?。！？\\n]{1,32}?)\\s*이후(?:에는|에|부터)?\\s*$");

    private static final Pattern DERIVED_RESTRICTION_LEAD = Pattern.compile(
            "^(?:(?:(?:계산|합산|차감)\\s*결과|가정\\s*계산으로|예를\\s*들어)\\s*[,，]?\\s*"
                    + "|(?:사용량|요금|금액|비용)(?:을|를)\\s*(?:계산|합산|차감)(?:하면|해도)\\s*"
                    + "|할인\\s*적용\\s*후\\s*)+");
    private static final Pattern RESTRICTION_CLAIM = Pattern.compile(
            "(" + RESTRICTION_ACTION + ")\\s*(?:(?:하(?:는|기)|는|기)(?:\\s*(?:것|거))?)?\\s*(?:을|를|이|가|은|는)?\\s*("
                    + "(?:할\\s*)?수\\s*(?:가\\s*)?없(?:습니다|어요|으며|지만|네요|는데요|다(?=\\s*[.!?。！？]|\\s*$)|고)"
                    + "|가능하지\\s*않(?:습니다|아요)|불가능(?:합니다|해요|하며|하고|하지만)|불가(?:합니다|해요|하며|하고|하지만)"
                    + "|(?:하지|되지)\\s*않(?:습니다|아요|으며|지만|아(?!도|야)|고)|안\\s*(?:됩니다|돼요)"
                    + "|제한(?:됩니다|되어\\s*있습니다|이\\s*있습니다)|허용되지\\s*않(?:습니다|아요)"
                    + "|불가(?=\\s*[.!。]|\\s*$))"
                    + "|(미지원|미제공)(?:입니다|예요|이며|이고|(?=\\s*[.!。]|\\s*$))",
            Pattern.CASE_INSENSITIVE);
    private static final String RESTRICTION_PROVISION_ITEM = "(?:음성\\s*)?통화|음성|문자(?:\\s*메시지)?";
    private static final Pattern RESTRICTION_PROVISION_ITEMS = Pattern.compile(RESTRICTION_PROVISION_ITEM);
    private static final String RESTRICTION_PROVISION_LIST = "(?:" + RESTRICTION_PROVISION_ITEM + ")"
            + "(?:\\s*(?:와|과|및|[·,/])\\s*(?:" + RESTRICTION_PROVISION_ITEM + ")){0,4}";
    private static final Pattern RESTRICTION_PROVISION_CONTEXT = Pattern.compile(
            "(?<items>" + RESTRICTION_PROVISION_LIST + ")(?:\\s*서비스)?\\s*(?:은|는|이|가|을|를)?\\s*$");
    private static final Pattern RESTRICTION_PROVISION_ABSENCE = Pattern.compile(
            "(?<items>" + RESTRICTION_PROVISION_LIST + ")(?:\\s*서비스)?\\s*(?:은|는|이|가)?\\s*"
                    + "없(?:습니다|어요|으며|고|어(?!도|야)|다(?=\\s*[.!?。！？]|\\s*$))");
    private static final Pattern RESTRICTION_PROVISION_LEAD = Pattern.compile(
            "(?:월\\s*)?(?<money>" + WON_AMOUNT + ")\\s*(?:에|으로)?\\s*(?:데이터\\s*)?(?<data>" + DECIMAL_NUMBER
                    + "\\s*(?:GB|MB|기가바이트|메가바이트))\\s*(?:의\\s*)?(?:데이터)?(?:을|를)?\\s*"
                    + "제공(?:하며|하고|되며|되고)\\s*[,，]?\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESTRICTION_DEVICE_OWNER = Pattern.compile(
            DEVICE_SCOPE.pattern() + "의(?=\\s|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESTRICTION_SETTING_CONTEXT = Pattern.compile(
            "^(.+?)(해지|정지|해제)할\\s*수\\s*있(?:습니다|어요)[.!。！]?$");
    private static final Pattern SEPARATE_PROCEDURE = Pattern.compile(
            "별도로\\s*(?:진행|신청)(?:되어야|해야|하셔야)"
                    + "|별도(?:의)?\\s*절차를\\s*거쳐야");
    private static final Pattern KNOWLEDGE_RESTRICTION = Pattern.compile(
            "(?<knowledgeAction>확인|알|안내|답변|조회|공개|출력|노출|생략|우회|면제)(?:하는|하기|할)?\\s*(?:것|수)?\\s*(?:은|는|이|가)?\\s*$"
                    + "|(?:정보|자료|문서|근거|조회\\s*결과|정확한\\s*금액|요금액|위치|주소|좌표|원문)"
                    + "(?:가|이|는|은|를|을|로)?\\s*(?:(?:현재|아직|지금|따로)\\s*)?$"
                    + "|(?:무료로|시스템\\s*프롬프트|비밀\\s*토큰|API\\s*키)[^.!?。！？\\n]{0,20}$"
                    + "|(?:타인|다른\\s*고객|남의)[^.!?。！？\\n]{0,24}(?:정보|내역)[^.!?。！？\\n]{0,12}$");

    private static final Pattern PERSONAL_DATA_RESTRICTION_OBJECT = Pattern.compile(
            "(?:" + USER_SUBJECT.pattern() + "|" + ACCOUNT_RECORD.pattern()
                    + "|(?:개인|사용자|회원)(?:정보|자료|데이터)|정보|자료|내역|기록|코드|합계|사용량|데이터량)"
                    + "(?:(?:관련된)?(?:정보|자료|데이터)없(?:어|기때문|으므로))?"
                    + "(?:(?:확인|계산|합산)(?:하|할)?(?:거나|여|해서))?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BUSINESS_SCOPE = Pattern.compile(
            "(?<port>번호(?:만|를)?\\s*이동|통신사(?:만|를)?\\s*(?:옮기|옮길|옮겨|바꾸|바꿀|바꿔|변경))"
                    + "|(?<device>기기\\s*변경|단말(?:기)?\\s*(?:교체|변경)|(?:휴대폰|스마트폰|폰|기기)(?:만|을|를)?\\s*(?:바꾸|바꿀|바꿔|교체|변경))"
                    + "|(?<number>번호(?:만|를)?\\s*(?:변경|바꾸|바꿀|바꿔))"
                    + "|(?<stop>(?:회선|이용|일시)?\\s*정지)|(?<installment>할부\\s*(?:금|잔액))");
    private static final Pattern ANSWER_UNAVAILABLE = Pattern.compile(
            "(?:확인|알|판단|안내|답변|확정)(?:하|되|해)?(?:기|할|하는)?\\s*(?:수\\s*없|어렵|못)"
                    + "|(?:정보|자료|근거|결과)[^.!?。！？\\n]{0,24}(?:없|부족|명시되지\\s*않)");
    private static final List<String> SWITCH_OPERATIONS = List.of("번호이동", "기기변경", "번호변경");
    private static final Pattern GENERIC_FOLLOWUP = Pattern.compile(
            "^(?:(?:더|그\\s*밖에|그밖에|그\\s*외에|그외에|추가로|혹시|다른)\\s*)*"
                    + "(?:(?:필요(?:하신|한)\\s*(?:것|내용|도움|정보)|궁금(?:하신|한)\\s*(?:점|것|내용)|문의(?:하실|할)\\s*(?:것|내용))"
                    + "(?:이|가|은|는)?\\s*(?:있나요|있으신가요|있으실까요|있습니까|있을까요|있으세요)"
                    + "|(?:무엇|어떤\\s*것)(?:을|를)?\\s*(?:도와\\s*드릴까요|도와드릴까요|도와줄까요))"
                    + "[?？.!。！]*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FOLLOWUP_INFORMATION_REQUEST = Pattern.compile(
            "(?:(?<input>입력)(?:해|하여)?|알려|말씀(?:해|하여)?|말해|설명(?:해|하여)?|보내|제공(?:해|하여)?)\\s*"
                    + "(?:주(?:세요|십시오|시면|시기\\s*바랍니다)|줘(?:요)?)");
    // 도구가 받을 검색 기준의 필드만 구분합니다. 특정 답변 문장을 허용 목록으로 두지 않습니다.
    private static final String STORE_LOCATION_FIELD =
            "(?:지역(?:명)?|지명|동네(?:\\s*이름)?|역(?:\\s*(?:이름|명))?|위치)";
    private static final String STORE_LOCATION_FIELD_MODIFIER =
            "(?:(?:어느|어떤|원하(?:는|시는)|희망(?:하는|하시는)?|정확한|기준(?:이\\s*될)?|현재"
                    + "|검색할|조회할|찾으시는|매장\\s*검색(?:을\\s*위한)?)\\s*)*";
    private static final Pattern STORE_LOCATION_INPUT_FIELDS = Pattern.compile(
            "^" + STORE_LOCATION_FIELD_MODIFIER + STORE_LOCATION_FIELD
                    + "(?:\\s*(?:이나|또는|혹은|와|과|및|·|/|,)\\s*"
                    + STORE_LOCATION_FIELD_MODIFIER + STORE_LOCATION_FIELD + ")*"
                    + "(?:\\s*(?:을|를|이|가|은|는))?\\s*(?:인지|어디(?:인지)?|어느\\s*곳)?$");
    private static final Pattern EXTERNAL_INFORMATION_DESTINATION = Pattern.compile(
            "(?:고객\\s*센터|상담(?:원|사)|매장(?:\\s*직원)?|직원|대리점|통신사|담당자|앱|홈페이지|웹사이트|신청서|화면)"
                    + "(?:에게|한테|으로|로|에(?!서))[^,;.!?。！？\\n]{0,16}$");
    private static final Pattern DOCUMENT_PERIOD_DOCUMENT = Pattern.compile(
            REQUIREMENT_DOCUMENT.pattern() + "|[가-힣]{2,}\\s*(?:등본|초본)");
    private static final Pattern DOCUMENT_ISSUANCE_PERIOD = Pattern.compile(
            "발급(?:일|\\s*시점)?(?:로부터|부터|한\\s*지|된\\s*지|받은\\s*지)?\\s*"
                    + DECIMAL_NUMBER + "\\s*(?:개월|년|일)\\s*(?:이내|이하|미만)");


    // 참고 자료에 명시된 조회 실패만 구분합니다. 이 패턴은 문장 차단 조건을 바꾸지 않습니다.
    private static final Pattern MISSING_LOOKUP_DATA = Pattern.compile(
            "찾을\\s*수\\s*없|조회하지\\s*못|제공되지\\s*않|정보[^.!?。！？\\n]{0,24}(?:없|부족)");

    private final ForbiddenWordFilterService forbiddenWordFilterService;
    // 지표를 보지 않는 테스트는 전역 레지스트리를 씀. 등록된 저장소가 없으면 기록하지 않음
    private final MeterRegistry metrics;

    public AiOutputGuardService(ForbiddenWordFilterService forbiddenWordFilterService) {
        this(forbiddenWordFilterService, Metrics.globalRegistry);
    }

    @Autowired
    public AiOutputGuardService(ForbiddenWordFilterService forbiddenWordFilterService, MeterRegistry metrics) {
        this.forbiddenWordFilterService = forbiddenWordFilterService;
        this.metrics = metrics;
    }

    /** 후보 단어 검증 전용입니다. 동일한 문장 분리·금칙어 검사만 재사용하고 FAQ 사실 검사는 실행하지 않습니다. */
    public String previewForbiddenWordStage(String answer) {
        if (answer == null || answer.isBlank()) return "";
        return splitSentences(answer).stream().filter(sentence -> !containsForbiddenWord(sentence))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    /** 검사에 사용한 기존 규칙의 식별자입니다. FAQ 번호·사용자 문구는 식별자로 쓰지 않습니다. */
    private enum GuardRule {
        FORBIDDEN_WORD, SENSITIVE_DISCLOSURE, POLICY_OVERRIDE, THIRD_PARTY_LOOKUP, HOSTILE_CONTENT,
        QUANTITY_CONFLICT, CONDITION_CONFLICT, UNSUPPORTED_BENEFIT, UNSUPPORTED_SOURCE,
        UNSUPPORTED_OPERATION, INTERNAL_DISCLOSURE_BLOCK, REQUESTED_SUFFIX, FAQ_REFERENCE,
        THIRD_PARTY_FOLLOWUP, FACT_FOLLOWUP, DEPENDENT_CONCLUSION, LOST_CONDITION,
        INDEPENDENT_SUPPORT_MISSING, CONTRADICTORY_AVAILABILITY, DOCUMENT_REQUIREMENT,
        SCOPE_MISMATCH, UNSUPPORTED_RESTRICTION, REQUEST_SCOPE_MISMATCH, EMPTY_HEADING,
        RESPONSE_METADATA, INVALID_EVIDENCE_ID, UNPROVIDED_EVIDENCE_ID, EMPTY_ANSWER, NO_SAFE_CONTENT,
        NO_REQUESTED_ANSWER, NO_USABLE_ANSWER, FOLLOWUP_QUESTION, WHOLE_ANSWER_REJECTION
    }

    /** 호출마다 생성합니다. 제거 수는 실제 삭제한 문장·절·블록 단위이며 한 단위를 중복 집계하지 않습니다. */
    private static final class InspectionTrace {
        private final EnumMap<GuardRule, Integer> removals = new EnumMap<>(GuardRule.class);
        private final EnumMap<GuardRule, Integer> cleanups = new EnumMap<>(GuardRule.class);
        private final EnumMap<GuardRule, Integer> warnings = new EnumMap<>(GuardRule.class);
        private int removedUnits;
        private GuardRule rejection;

        private void removed(GuardRule... rules) {
            removed(1, rules);
        }

        private void removed(int count, GuardRule... rules) {
            if (count == 0) return;
            removedUnits += count;
            // 여러 규칙이 한 삭제에 함께 적용될 수 있어 규칙별 합계는 전체 제거 수보다 클 수 있습니다.
            for (GuardRule rule : rules) if (rule != null) removals.merge(rule, count, Integer::sum);
        }

        private void cleaned(GuardRule rule) {
            cleanups.merge(rule, 1, Integer::sum);
        }

        private void warned(GuardRule rule) {
            warnings.merge(rule, 1, Integer::sum);
        }

        private void rejected(GuardRule rule) {
            rejection = rule;
        }
    }

    /** 근거 번호 오류는 경고만 기록합니다. 답변 본문은 모델이 적은 번호와 무관하게 실제 제공 자료로 검사합니다. */
    public void validateEvidenceIds(List<String> evidenceIds, AnswerMaterials materials) {
        long startedAt = System.nanoTime();
        InspectionTrace trace = new InspectionTrace();
        validateEvidenceIds(evidenceIds, materials, trace);
        if (!trace.warnings.isEmpty())
            logInspection("EVIDENCE", "WARNING", Set.of(), null, null, startedAt, trace);
    }

    private void validateEvidenceIds(List<String> evidenceIds, AnswerMaterials materials, InspectionTrace trace) {
        if (evidenceIds == null || evidenceIds.isEmpty()) return;
        List<String> providedIds = materials.faqs().stream().map(faq -> String.valueOf(faq.faqId())).toList();
        for (String evidenceId : evidenceIds) {
            if (!StringUtils.hasText(evidenceId)) {
                trace.warned(GuardRule.INVALID_EVIDENCE_ID);
                continue;
            }
            // 현재 프롬프트의 숫자 ID와 저장 테스트의 FAQ- 접두사·앞자리 0 표기를 같은 번호로 비교합니다.
            Matcher id = EVIDENCE_FAQ_ID.matcher(inspect(evidenceId));
            if (!id.matches()) trace.warned(GuardRule.INVALID_EVIDENCE_ID);
            else if (!providedIds.contains(id.group(1).replaceFirst("^0+(?=\\d)", "")))
                trace.warned(GuardRule.UNPROVIDED_EVIDENCE_ID);
        }
    }

    /** 기존 규칙으로 문제 내용이 탐지되면 답변 전체를 거절합니다. DB나 외부 모델은 호출하지 않습니다. */
    public String filterAnswer(String answer, AnswerMaterials materials, StoreMapResult storeMap) {
        long startedAt = System.nanoTime();
        Set<Reason> reasons = EnumSet.noneOf(Reason.class);
        InspectionTrace trace = new InspectionTrace();
        try {
            String filtered = filterAnswer(answer, materials, storeMap, reasons, trace);
            logInspection("BODY", filtered.equals(answer) ? "PASS" : "FILTERED", reasons, answer, filtered, startedAt, trace);
            return filtered;
        } catch (LlmException exception) {
            if (exception.getErrorCode() == LlmErrorCode.LLM_OUTPUT_REJECTED) {
                logInspection("BODY", "REJECTED", reasons, answer, null, startedAt, trace);
            }
            throw exception;
        }
    }

    /** 원문·근거 본문·개인정보는 기록하지 않고 기존 검사 사유와 적용 결과만 남깁니다. */
    private void logInspection(String stage, String outcome, Set<Reason> reasons,
            String answer, String filtered, long startedAt, InspectionTrace trace) {
        Set<Reason> loggedReasons = ("FILTERED".equals(outcome) || "REJECTED".equals(outcome)) && reasons.isEmpty()
                ? Set.of(Reason.UNCLASSIFIED) : reasons;
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
        String message = "출력 가드 적용 결과: 단계={}, 결과={}, 사유={}, 규칙별제거수={}, 제거단위수={}, 정리규칙={}, 경고규칙={}, 거절사유={}, 입력문자수={}, 출력문자수={}, 소요시간={}ms";
        Object[] values = {stage, outcome, loggedReasons, trace.removals, trace.removedUnits, trace.cleanups,
                trace.warnings,
                trace.rejection == null ? "NONE" : trace.rejection, answer == null ? 0 : answer.length(),
                filtered == null ? 0 : filtered.length(), elapsedMillis};
        if ("REJECTED".equals(outcome) || !trace.warnings.isEmpty()) log.warn(message, values);
        else log.info(message, values);
        // 거절률 = stage=body 중 outcome=rejected 비율. rule은 거절한 규칙(GuardRule)이고 그 밖에는 none
        metrics.counter("ai.output.guard",
                "stage", stage.toLowerCase(Locale.ROOT),
                "outcome", outcome.toLowerCase(Locale.ROOT),
                "rule", trace.rejection == null ? "none" : trace.rejection.name().toLowerCase(Locale.ROOT)).increment();
    }

    /** 본문 검사 결과를 내부에서 읽습니다. 기존 호출 경로·저장·관리자 처리는 연결하지 않습니다. */
    public OutputGuardResult inspectAnswer(String answer, AnswerMaterials materials, StoreMapResult storeMap) {
        return inspectAnswer(answer, null, materials, storeMap);
    }

    /** 기존 파서가 남긴 값만 검사합니다. 파서가 보정한 JSON 자료형·누락 필드는 복원하지 않습니다. */
    public OutputGuardResult inspectAnswer(String answer, List<String> evidenceIds,
            AnswerMaterials materials, StoreMapResult storeMap) {
        Set<Reason> reasons = EnumSet.noneOf(Reason.class);
        InspectionTrace trace = new InspectionTrace();
        long startedAt = System.nanoTime();
        try {
            validateEvidenceIds(evidenceIds, materials, trace);
            if (StringUtils.hasText(answer)) recordMaterialShortages(materials, storeMap, reasons);
            String filtered = filterAnswer(answer, materials, storeMap, reasons, trace);
            recordUnansweredScopes(filtered, materials, storeMap, reasons);
            logInspection("BODY", filtered.equals(answer) ? "PASS" : "FILTERED", reasons, answer, filtered, startedAt, trace);
            return new OutputGuardResult(filtered, false, reasons, trace.removedUnits > 0);
        } catch (LlmException exception) {
            if (exception.getErrorCode() != LlmErrorCode.LLM_OUTPUT_REJECTED) throw exception;
            // 같은 오류 코드를 FAQ 부족으로 바꾸지 않습니다. 판별 근거가 없으면 미분류입니다.
            boolean explained = reasons.contains(Reason.POLICY_VIOLATION) || reasons.contains(Reason.FORMAT_ERROR)
                    || reasons.contains(Reason.FALSE_EXECUTION) || reasons.contains(Reason.ANSWER_ERROR);
            if (!explained) recordReason(reasons, Reason.UNCLASSIFIED);
            logInspection("BODY", "REJECTED", reasons, answer, null, startedAt, trace);
            return new OutputGuardResult(null, true, reasons);
        }
    }

    private String filterAnswer(String answer, AnswerMaterials materials, StoreMapResult storeMap, Set<Reason> reasons,
            InspectionTrace trace) {
        if (!StringUtils.hasText(answer)) {
            recordReason(reasons, Reason.FORMAT_ERROR);
            trace.rejected(GuardRule.EMPTY_ANSWER);
            throw new LlmException(LlmErrorCode.LLM_OUTPUT_REJECTED);
        }
        List<String> sources = readSources(materials);
        List<QuantityFact> facts = new ArrayList<>();
        sources.stream().filter(StringUtils::hasText).flatMap(source -> splitSentences(source).stream())
                .forEach(source -> facts.addAll(readFacts(inspect(source))));
        // 회원의 청구 금액·데이터량은 일반 수치 검사가 아니라 회선·월·항목을 맞춰 실제 기록과 비교합니다.
        UserRecordClaims userRecords = userRecords(materials);
        List<ScopedQuantity> moneyEvidence = readMoneyEvidence(materials, sources);
        List<String> faqSources = materials.faqs().stream().map(faq -> faq.answer())
                .filter(StringUtils::hasText).map(this::inspect).toList();
        // 대상별 근거는 한 번 모아 수치 비교와 뒤의 대상 검사에서 함께 사용합니다.
        List<ScopedAnswerFact> scopeEvidence = new ArrayList<>();
        List<String> payoffGoals = new ArrayList<>();
        materials.faqs().forEach(faq -> addScopeEvidence(faq.answer(), faq.question(), sources, scopeEvidence, payoffGoals));
        materials.sections().forEach(section ->
                addScopeEvidence(section.content(), materials.question(), sources, scopeEvidence, payoffGoals));
        scopeEvidence.stream().filter(fact -> fact.kind() == AnswerScopeKind.PLAN_PRICE)
                .map(ScopedAnswerFact::quantity).forEach(facts::add);
        if (storeMap != null) {
            facts.add(new QuantityFact("반경", BigDecimal.valueOf(storeMap.radiusKm()).multiply(BigDecimal.valueOf(1000)), "m", QuantityBound.EXACT));
            storeMap.stores().forEach(store -> facts.add(new QuantityFact("거리",
                    BigDecimal.valueOf(store.distanceKm()).setScale(1, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(1000)), "m", QuantityBound.EXACT)));
        }

        // 공개 머리말 뒤의 여러 줄 지시도 함께 제거합니다. 원문·자료·프롬프트는 변경하지 않습니다.
        String withoutSuffix = removeRequestedSuffix(answer, materials.question());
        if (!withoutSuffix.equals(answer)) {
            recordReason(reasons, Reason.FORMAT_ERROR);
            trace.cleaned(GuardRule.REQUESTED_SUFFIX);
        }
        String withoutMetadata = removeResponseMetadata(withoutSuffix, materials.question());
        String withoutDisclosures = withoutMetadata;
        if (!withoutMetadata.equals(withoutSuffix)) {
            trace.cleaned(GuardRule.RESPONSE_METADATA);
            recordReason(reasons, Reason.FORMAT_ERROR);
        }
        for (String block : internalDisclosureBlocks(withoutMetadata)) {
            int occurrences = withoutDisclosures.split(Pattern.quote(block), -1).length - 1;
            withoutDisclosures = withoutDisclosures.replace(block, "");
            trace.removed(occurrences, GuardRule.INTERNAL_DISCLOSURE_BLOCK);
        }
        List<String> kept = new ArrayList<>();
        boolean removed = !withoutDisclosures.equals(withoutSuffix);
        if (!withoutDisclosures.equals(withoutMetadata)) recordReason(reasons, Reason.POLICY_VIOLATION);
        for (String paragraph : withoutDisclosures.split("\\R\\s*\\R")) {
            List<String> safeParagraph = new ArrayList<>();
            List<String> sentences = new ArrayList<>();
            boolean separatedClauses = false;
            for (String sentence : splitSentences(paragraph)) {
                int previousSize = sentences.size();
                String inspected = inspect(sentence);
                boolean mixedClaim = !STORE_SUBJECT.matcher(inspected).find()
                        && (hasUnsupportedQueryPromise(inspected, materials, storeMap)
                        || RESTRICTION_CLAIM.matcher(inspected).find());
                addAnswerClauses(sentence, sentences, true, mixedClaim
                        ? clause -> hasIndependentQueryFact(clause, materials, sources) : null);
                separatedClauses |= mixedClaim && sentences.size() - previousSize > 1;
            }
            boolean removedThirdPartyLookup = false;
            boolean removedFactClaim = false;
            boolean removedSourcePolicy = false;
            boolean reviewRestoredParagraph = false;
            // 줄을 나눠 쓴 조회 요구를 일부만 삭제해 타인 정보라는 불완전한 본문을 남기지 않습니다.
            boolean crossLineLookup = hasThirdPartyLookup(inspect(paragraph))
                    && sentences.stream().noneMatch(sentence -> hasThirdPartyLookup(inspect(sentence)));
            boolean removedInParagraph = crossLineLookup;
            if (crossLineLookup) recordReason(reasons, Reason.POLICY_VIOLATION);
            boolean lostCondition = crossLineLookup || removed && DEPENDENT_SENTENCE.matcher(inspect(paragraph)).find();
            for (String sentence : sentences) {
                String inspected = inspect(sentence);
                Matcher followup = THIRD_PARTY_FOLLOWUP.matcher(inspected);
                if (removedThirdPartyLookup && followup.find()
                        && !isRejectedOrReported(inspected.substring(followup.end()))) {
                    removedInParagraph = true;
                    trace.removed(GuardRule.THIRD_PARTY_FOLLOWUP);
                    continue;
                }
                if (removedFactClaim && FACT_FOLLOWUP.matcher(inspected).find()) {
                    removedInParagraph = true;
                    trace.removed(GuardRule.FACT_FOLLOWUP);
                    continue;
                }
                boolean thirdPartyLookup = hasThirdPartyLookup(inspected);
                // 회원 기록과 비교한 값은 지운 문장으로 일반 수치 검사를 해, 같은 값을 두 기준으로 판정하지 않습니다.
                UserRecordClaims.Verdict installmentVerdict = checkRecordedInstallmentAmounts(
                        withoutRecordedPlanNames(inspected, materials), materials);
                UserRecordClaims.Verdict recordVerdict = userRecords.check(installmentVerdict.unhandled(), faqSources);
                boolean quantityConflict = installmentVerdict.conflict() || recordVerdict.conflict()
                        || hasQuantityConflict(recordVerdict.unhandled(), facts, materials.question(), moneyEvidence);
                boolean requirementConflict = hasRequirementConflict(inspected, sources);
                GuardRule policyRule = findPolicyViolation(sentence, inspected, sources, thirdPartyLookup);
                boolean policyViolation = policyRule != null;
                // 기존 단락 평가 순서를 유지하고, 같은 검사를 다시 실행하지 않고 적용 규칙만 기록합니다.
                GuardRule contentRule = policyViolation ? policyRule : quantityConflict ? GuardRule.QUANTITY_CONFLICT
                        : hasUnsupportedFreeBenefit(inspected, sources) ? GuardRule.UNSUPPORTED_BENEFIT
                        : requirementConflict ? GuardRule.CONDITION_CONFLICT
                        : hasUnsupportedSource(inspected, materials) ? GuardRule.UNSUPPORTED_SOURCE : null;
                boolean unsafeContent = contentRule != null;
                boolean unsupportedOperation = hasUnsupportedOperation(inspected, materials, storeMap);
                if (unsafeContent || unsupportedOperation) {
                    trace.removed(contentRule, unsupportedOperation ? GuardRule.UNSUPPORTED_OPERATION : null);
                    if (policyViolation) recordReason(reasons, Reason.POLICY_VIOLATION);
                    if (quantityConflict || requirementConflict || unsafeContent && !policyViolation)
                        recordReason(reasons, Reason.ANSWER_ERROR);
                    if (unsupportedOperation) recordReason(reasons, Reason.FALSE_EXECUTION);
                    removedInParagraph = true;
                    removedThirdPartyLookup |= thirdPartyLookup;
                    removedFactClaim |= quantityConflict || requirementConflict;
                    removedSourcePolicy = requirementConflict && SOURCE_POLICY_CLAIM.matcher(inspected).find();
                    // 조회 약속에만 붙은 상황 설명 때문에 독립된 요금·절차 안내까지 삭제하지 않습니다.
                    lostCondition |= CONDITION.matcher(inspected).find()
                            && (unsafeContent || !hasOnlyQueryCondition(inspected));
                    continue;
                }
                // 연결어만으로 정상 문단 전체를 버리지 않고, 삭제한 전제에 의존하는 후속 문장만 제거합니다.
                Matcher dependent = DEPENDENT_SENTENCE.matcher(inspected);
                if (removedInParagraph && dependent.find()) {
                    String conclusion = inspected.substring(dependent.end()).strip();
                    String withoutTopic = conclusion.replaceFirst("^[가-힣\\s]{1,20}?(?:은|는)\\s+", "");
                    boolean sourceExplanation = hasSourceClaim(conclusion, sources) || hasSourceClaim(withoutTopic, sources);
                    reviewRestoredParagraph |= !removedSourcePolicy || !sourceExplanation;
                    boolean independent = !hasUnsupportedDependentConclusion(inspected, materials, sources)
                            && (QUESTION_END.matcher(inspected).find() || INDEPENDENT_CHECK_GUIDANCE.matcher(conclusion).find()
                            || sourceExplanation && ASSERTED_CLAUSE_END.matcher(conclusion.replaceFirst("[.!。！]+$", "")).find());
                    if (!independent && !(removedSourcePolicy && sourceExplanation)) {
                        trace.removed(GuardRule.DEPENDENT_CONCLUSION);
                        continue;
                    }
                }
                safeParagraph.add(sentence);
            }
            removed |= removedInParagraph || separatedClauses;
            if (!lostCondition) {
                if (reviewRestoredParagraph) {
                    for (String sentence : safeParagraph) {
                        if (hasIndependentSentenceSupport(sentence, materials, sources)) kept.add(sentence);
                        else trace.removed(GuardRule.INDEPENDENT_SUPPORT_MISSING);
                    }
                } else kept.addAll(safeParagraph);
            } else {
                trace.removed(safeParagraph.size(), crossLineLookup ? GuardRule.THIRD_PARTY_LOOKUP : GuardRule.LOST_CONDITION);
            }
        }
        if (kept.isEmpty()) {
            trace.rejected(GuardRule.NO_SAFE_CONTENT);
            throw new LlmException(LlmErrorCode.LLM_OUTPUT_REJECTED);
        }
        // 문제 없는 답변은 원문을 유지합니다. 합친 뒤에만 생기는 금칙어도 기존 서비스로 검사합니다.
        String filtered = removed ? String.join("\n", kept) : withoutSuffix.strip();
        String beforeReferences = filtered;
        filtered = removeFaqReferences(filtered);
        if (!beforeReferences.equals(filtered)) trace.cleaned(GuardRule.FAQ_REFERENCE);
        String beforeCheck = filtered;
        filtered = removeContradictoryAvailability(filtered, materials, sources, trace);
        recordAnswerChange(reasons, beforeCheck, filtered);
        beforeCheck = filtered;
        filtered = removeUnsupportedRequirements(filtered, materials, sources, trace);
        recordAnswerChange(reasons, beforeCheck, filtered);
        beforeCheck = filtered;
        filtered = removeMismatchedScopes(filtered, materials, sources, scopeEvidence, payoffGoals, trace);
        recordAnswerChange(reasons, beforeCheck, filtered);
        beforeCheck = filtered;
        filtered = removeUnsupportedRestrictions(filtered, materials, sources, trace);
        recordAnswerChange(reasons, beforeCheck, filtered);
        beforeCheck = filtered;
        filtered = removeFollowUpQuestions(filtered, materials, storeMap, trace);
        recordAnswerChange(reasons, beforeCheck, filtered);
        filtered = filterRequestedScopes(filtered, answer, materials, reasons, trace);
        if (!StringUtils.hasText(filtered) || !filtered.equals(withoutSuffix.strip())
                && splitSentences(filtered).stream().allMatch(sentence ->
                        GENERIC_FOLLOWUP.matcher(inspect(sentence)).matches()
                        || FAQ_REFERENCE_NOTICE.matcher(inspect(sentence)).matches())) {
            trace.rejected(GuardRule.NO_USABLE_ANSWER);
            throw new LlmException(LlmErrorCode.LLM_OUTPUT_REJECTED);
        }
        String inspectedFiltered = inspect(filtered);
        GuardRule finalRule = findPolicyViolation(filtered, inspectedFiltered, sources,
                hasThirdPartyLookup(inspectedFiltered));
        if (finalRule != null) {
            trace.rejected(finalRule);
            recordReason(reasons, Reason.POLICY_VIOLATION);
            throw new LlmException(LlmErrorCode.LLM_OUTPUT_REJECTED);
        }
        // 기존 검사의 문장·절·블록 탐지를 재사용하되 정리된 일부 답변을 반환하지 않습니다.
        // 출처 표시 정리와 근거 번호 경고는 본문 위반 탐지와 구분합니다.
        if (trace.removedUnits > 0 || trace.cleanups.containsKey(GuardRule.REQUESTED_SUFFIX)
                || trace.cleanups.containsKey(GuardRule.RESPONSE_METADATA)) {
            trace.rejected(GuardRule.WHOLE_ANSWER_REJECTION);
            throw new LlmException(LlmErrorCode.LLM_OUTPUT_REJECTED);
        }
        return filtered;
    }

    private boolean hasFollowUpInformationRequest(String sentence) {
        Matcher request = FOLLOWUP_INFORMATION_REQUEST.matcher(sentence);
        while (request.find()) {
            if (isRejectedOrReported(sentence.substring(request.end()))) continue;
            String prefix = sentence.substring(0, request.start());
            // 앱 입력 절차나 외부 상담원에게 전달하는 방법은 챗봇에 다시 답하라는 요청과 구분합니다.
            if (EXTERNAL_INFORMATION_DESTINATION.matcher(prefix).find()
                    || request.group("input") != null && PROVIDER_GUIDANCE.matcher(prefix).find()) continue;
            return true;
        }
        return false;
    }

    private GuardRule findPolicyViolation(String sentence, String inspected, List<String> sources,
            boolean thirdPartyLookup) {
        if (containsForbiddenWord(sentence)) return GuardRule.FORBIDDEN_WORD;
        if (hasSensitiveDisclosure(inspected)) return GuardRule.SENSITIVE_DISCLOSURE;
        if (hasPolicyOverride(inspected)) return GuardRule.POLICY_OVERRIDE;
        if (thirdPartyLookup) return GuardRule.THIRD_PARTY_LOOKUP;
        return hasHostileContent(inspected, sources) ? GuardRule.HOSTILE_CONTENT : null;
    }

    /** 조회 전 매장 검색 기준 입력은 구분하되, 일반 되묻기와 추가 정보 요구는 기존대로 거절합니다. */
    private String removeFollowUpQuestions(String answer, AnswerMaterials materials, StoreMapResult storeMap,
            InspectionTrace trace) {
        List<String> kept = new ArrayList<>();
        boolean removed = false;
        for (String sentence : splitSentences(answer)) {
            List<String> clauses = new ArrayList<>();
            addAnswerClauses(sentence, clauses, true, null);
            for (String clause : clauses) {
                String inspected = inspect(clause);
                boolean followUp = QUESTION_END.matcher(inspected).find() || GENERIC_FOLLOWUP.matcher(inspected).matches()
                        || hasFollowUpInformationRequest(inspected);
                if (followUp && !isStoreLocationInputRequest(inspected, materials, storeMap)) {
                    trace.removed(GuardRule.FOLLOWUP_QUESTION);
                    removed = true;
                } else kept.add(clause);
            }
        }
        return removed ? String.join("\n", kept) : answer;
    }

    private boolean isStoreLocationInputRequest(String sentence, AnswerMaterials materials, StoreMapResult storeMap) {
        // 서버가 선택한 매장 도구 문맥과 실제 조회 결과를 사용합니다. 모델의 status나 문구로 문맥을 만들지 않습니다.
        if (!materials.tools().contains(AiTool.STORE_SEARCH) || storeMap != null) return false;
        String content = sentence.replaceFirst("[.!?。！？]+$", "").strip();
        Matcher request = FOLLOWUP_INFORMATION_REQUEST.matcher(content);
        if (request.find()) {
            // 요구하는 필드 전체가 검색 기준이어야 합니다. 다른 정보나 뒤따르는 지시를 함께 허용하지 않습니다.
            return STORE_LOCATION_INPUT_FIELDS.matcher(content.substring(0, request.start()).strip()).matches()
                    && content.substring(request.end()).isBlank();
        }
        if (!QUESTION_END.matcher(sentence).find()) return false;
        Matcher ending = QUESTION_END.matcher(content);
        if (ending.find()) content = content.substring(0, ending.start()).strip();
        return STORE_LOCATION_INPUT_FIELDS.matcher(content).matches();
    }

    /** 명시된 업무·기기만 비교합니다. 모호한 주제나 생략된 대상은 추측하지 않습니다. */
    private String filterRequestedScopes(String answer, String originalAnswer, AnswerMaterials materials, Set<Reason> reasons,
            InspectionTrace trace) {
        if (!StringUtils.hasText(materials.question())) return answer;
        List<ScopedAnswerFact> requests = readRequestedScopes(inspect(materials.question()));
        if (requests.isEmpty() || !StringUtils.hasText(answer)) return answer;
        List<String> askedOperations = requests.stream().filter(request -> request.kind() == AnswerScopeKind.BUSINESS_OPERATION)
                .map(ScopedAnswerFact::target).filter(SWITCH_OPERATIONS::contains).toList();
        boolean hasOperationAnswer = readBusinessScopes(inspect(answer), false).stream().anyMatch(askedOperations::contains);
        List<String> kept = new ArrayList<>();
        boolean removed = false;
        for (String sentence : availabilitySentences(answer)) {
            String inspected = inspect(sentence);
            List<String> operations = readBusinessScopes(inspected, false).stream().filter(SWITCH_OPERATIONS::contains).toList();
            boolean mismatch = !askedOperations.isEmpty() && !operations.isEmpty()
                    && operations.stream().noneMatch(askedOperations::contains)
                    && !(hasOperationAnswer && inspected.matches("^(?:반면|이에\\s*비해|이와\\s*달리|그와\\s*달리).*"));
            List<ScopedAnswerFact> devices = requests.stream()
                    .filter(request -> request.kind() == AnswerScopeKind.SHARING_COMPATIBILITY).toList();
            List<ScopedAnswerFact> claims = readScopeFacts(inspected, materials.question(), false).stream()
                    .filter(claim -> claim.kind() == AnswerScopeKind.SHARING_COMPATIBILITY
                            || claim.kind() == AnswerScopeKind.SHARING_APPLICATION).toList();
            mismatch |= !devices.isEmpty() && !claims.isEmpty() && claims.stream().allMatch(claim ->
                    devices.stream().noneMatch(request -> compatibleDevice(request.target(), claim.target())));
            // 정보 부족·인용 설명은 단어 일치만으로 다른 업무라고 단정하지 않습니다.
            if (mismatch && !QUESTION_END.matcher(inspected).find() && !ANSWER_UNAVAILABLE.matcher(inspected).find()
                    && !REPORTED_OR_HYPOTHETICAL.matcher(inspected).find()) {
                recordReason(reasons, Reason.ANSWER_ERROR);
                trace.removed(GuardRule.REQUEST_SCOPE_MISMATCH);
                removed = true;
            } else {
                kept.add(sentence);
            }
        }
        String filtered = removed ? String.join("\n", kept) : answer;
        List<ScopedAnswerFact> covered = requests.stream().filter(request -> kept.stream()
                .anyMatch(sentence -> hasRequestCoverage(inspect(sentence), request, materials.question()))).toList();
        boolean changed = removed || !answer.equals(originalAnswer.strip());
        if (covered.isEmpty()) {
            if (!changed) return filtered;
            boolean removedRequested = requests.stream().anyMatch(request -> hasRequestedMention(inspect(originalAnswer), request));
            boolean onlyContext = kept.stream().allMatch(sentence -> isRequestContextOnly(inspect(sentence), requests));
            if (removed || removedRequested && onlyContext) {
                trace.rejected(GuardRule.NO_REQUESTED_ANSWER);
                throw new LlmException(LlmErrorCode.LLM_OUTPUT_REJECTED);
            }
            return filtered;
        }
        List<String> missing = requests.stream().filter(request -> !covered.contains(request))
                .map(this::readRequestLabel).distinct().toList();
        if (missing.isEmpty()) return filtered;
        // 안내는 고정 문구이며, 절차·가능 여부·개인별 값을 새로 만들지 않습니다.
        return filtered + "\n" + String.join(", ", missing) + UNANSWERED_SCOPE_NOTICE;
    }

    private void recordReason(Set<Reason> reasons, Reason reason) {
        if (reasons != null) reasons.add(reason);
    }

    private void recordAnswerChange(Set<Reason> reasons, String before, String after) {
        if (!before.equals(after)) recordReason(reasons, Reason.ANSWER_ERROR);
    }

    /** 검색 근거가 남지 않은 기존 하위 질문을 구분합니다. 답변의 자기 설명이나 status는 판정 근거로 쓰지 않습니다. */
    private void recordMaterialShortages(AnswerMaterials materials, StoreMapResult storeMap, Set<Reason> reasons) {
        for (var question : materials.subQuestions()) {
            if (!question.faqs().isEmpty() || !StringUtils.hasText(question.searchQuery())) continue;
            recordReason(reasons, hasMissingLookupData(question.searchQuery(), materials, storeMap)
                    ? Reason.USER_INFORMATION_OR_LOOKUP_MISSING : Reason.FAQ_SHORTAGE_SUSPECTED);
        }
    }

    /** 기존 업무·대상 검사로 실제 자료를 비교합니다. 해석할 수 없는 부족은 FAQ 부족으로 단정하지 않습니다. */
    private void recordUnansweredScopes(String answer, AnswerMaterials materials, StoreMapResult storeMap,
            Set<Reason> reasons) {
        if (!ANSWER_UNAVAILABLE.matcher(inspect(answer)).find()) return;
        if (hasMissingLookupData(materials.question(), materials, storeMap)) {
            recordReason(reasons, Reason.USER_INFORMATION_OR_LOOKUP_MISSING);
            return;
        }
        List<ScopedAnswerFact> requests = readRequestedScopes(inspect(materials.question()));
        List<String> sources = readSources(materials);
        boolean missingScope = requests.stream().anyMatch(request -> sources.stream()
                .noneMatch(source -> hasRequestCoverage(source, request, materials.question())));
        if (missingScope) recordReason(reasons, Reason.FAQ_SHORTAGE_SUSPECTED);
        else if (reasons.isEmpty()) recordReason(reasons, Reason.UNCLASSIFIED);
    }

    private boolean hasMissingLookupData(String question, AnswerMaterials materials, StoreMapResult storeMap) {
        if (!StringUtils.hasText(question)) return false;
        String inspected = inspect(question);
        boolean userContext = materials.faqs().stream().anyMatch(faq -> faq.intent() == Intent.USER_DATA)
                || materials.sections().stream().anyMatch(this::isUserDataSection);
        // USER_DATA FAQ나 '요금·데이터'라는 단어만으로 일반 지원 문의를 개인 조회로 분류하지 않습니다.
        boolean personalValue = ACCOUNT_RECORD.matcher(inspected).find()
                || PERSONAL_VALUE_REQUEST.matcher(inspected).find();
        if (userContext && (USER_SUBJECT.matcher(inspected).find() || personalValue)) {
            if (!hasUserData(materials)) return true;
            // 기본 회원 정보가 있으면, 청구·사용량 미제공 안내를 이름·가입일의 누락으로 확대하지 않습니다.
            return personalValue && materials.sections().stream()
                    .filter(this::isUserDataSection)
                    .anyMatch(section -> MISSING_LOOKUP_DATA.matcher(inspect(section.content())).find());
        }
        return storeMap == null && STORE_SUBJECT.matcher(inspected).find()
                && (materials.tools().contains(AiTool.STORE_SEARCH)
                    || materials.faqs().stream().anyMatch(faq -> faq.intent() == Intent.STORE_DATA)
                    || materials.sections().stream().anyMatch(section -> STORE_SUBJECT.matcher(section.title()).find()
                        && MISSING_LOOKUP_DATA.matcher(inspect(section.content())).find()));
    }

    private List<ScopedAnswerFact> readRequestedScopes(String question) {
        List<ScopedAnswerFact> requests = new ArrayList<>();
        for (String topic : readBusinessScopes(question, true)) {
            requests.add(new ScopedAnswerFact(AnswerScopeKind.BUSINESS_OPERATION, topic, null));
        }
        if (question.contains("쉐어링")) {
            String normalized = question.replace("랩톱", "노트북").replace("랩탑", "노트북");
            Matcher devices = DEVICE_SCOPE.matcher(normalized);
            while (devices.find()) {
                // 데이터를 제공하는 원회선 기기는 지원 여부를 묻는 대상과 구분합니다.
                if (normalized.substring(devices.end()).matches("(?s)^\\s*(?:의\\s*)?데이터(?:를|로|가|는|도).*")) continue;
                requests.add(new ScopedAnswerFact(AnswerScopeKind.SHARING_COMPATIBILITY, deviceName(devices), null));
            }
        }
        return requests.stream().distinct().toList();
    }

    private List<String> readBusinessScopes(String text, boolean question) {
        List<String> topics = new ArrayList<>();
        Matcher scope = BUSINESS_SCOPE.matcher(text);
        while (scope.find()) {
            String tail = text.substring(scope.end());
            // ‘바꾸지 않고’, ‘정지 중’ 같은 전제는 별도 질문으로 세지 않습니다.
            if (tail.matches("(?s)^\\s*(?:하)?지\\s*않(?:고|은|아도).*")) continue;
            if (tail.matches("(?s)^\\s*(?:은|는|이|가)?\\s*(?:말고|아니라|없이|제외).*")) continue;
            if (question && tail.matches("(?s)^\\s*(?:은|는|이|가)?\\s*(?:물어본|질문한|궁금한)\\s*(?:것|게|거)(?:은|는|이|가)?\\s*(?:아니|아닙).*")) continue;
            if (question && scope.group("stop") != null
                    && tail.matches("(?s)^\\s*(?:중|상태|기간|동안|하면|하면서|할\\s*때|했을\\s*때|시(?:에|도|는|\\s)).*")) continue;
            if (question && tail.matches("(?s)^[^.!?。！？\\n]{0,40}(?:답변|설명|안내)(?:을|를)?\\s*(?:받았|들었).*")) continue;
            if (question && tail.matches("(?s)^\\s*(?:러|하러|하려고|려고|할|하려는|려는|하고자)[^.!?。！？\\n]{0,20}.*")
                    && STORE_SUBJECT.matcher(tail.split("[.!?。！？\\n]", 2)[0]).find()) continue;
            // 조건에 따른 정상 절차도 답변입니다. 인용·부정 표현은 기존 판정을 유지합니다.
            if (!question && isUnassertedScope(tail) && !tail.matches("(?s)^\\s*(?:하)?(?:면|으면|다면|후|경우).*")) continue;
            String topic = scope.group("port") != null ? "번호이동" : scope.group("device") != null ? "기기변경"
                    : scope.group("number") != null ? "번호변경" : scope.group("stop") != null ? "회선 정지" : "할부금";
            topics.add(topic);
        }
        return topics.stream().distinct().toList();
    }

    private boolean hasRequestedMention(String sentence, ScopedAnswerFact request) {
        if (request.kind() == AnswerScopeKind.BUSINESS_OPERATION) return readBusinessScopes(sentence, true).contains(request.target());
        Matcher device = DEVICE_SCOPE.matcher(sentence.replace("랩톱", "노트북").replace("랩탑", "노트북"));
        while (device.find()) if (compatibleDevice(request.target(), deviceName(device))) return true;
        return false;
    }

    private boolean hasRequestCoverage(String sentence, ScopedAnswerFact request, String question) {
        if (isRequestContextOnly(sentence, List.of(request))) return false;
        if (request.kind() == AnswerScopeKind.BUSINESS_OPERATION) return readBusinessScopes(sentence, false).contains(request.target());
        if (ANSWER_UNAVAILABLE.matcher(sentence).find() && hasRequestedMention(sentence, request)) return true;
        return readScopeFacts(sentence, question, false).stream().anyMatch(claim ->
                (claim.kind() == AnswerScopeKind.SHARING_COMPATIBILITY || claim.kind() == AnswerScopeKind.SHARING_APPLICATION)
                        && compatibleDevice(request.target(), claim.target()));
    }

    private boolean isRequestContextOnly(String sentence, List<ScopedAnswerFact> requests) {
        if (ANSWER_UNAVAILABLE.matcher(sentence).find()) return false;
        if (INDEPENDENT_CHECK_GUIDANCE.matcher(sentence).find()) return true;
        return requests.stream().anyMatch(request -> request.kind() == AnswerScopeKind.SHARING_COMPATIBILITY)
                && sentence.contains("쉐어링") && !DEVICE_SCOPE.matcher(sentence).find();
    }

    private String readRequestLabel(ScopedAnswerFact request) {
        if (request.kind() == AnswerScopeKind.BUSINESS_OPERATION) return request.target();
        String name = switch (request.target()) {
            case "NOTEBOOK" -> "노트북";
            case "TABLET" -> "태블릿";
            case "WATCH" -> "워치";
            case "PHONE" -> "휴대폰";
            default -> request.target().replace("PHONE:", "") + " 휴대폰";
        };
        return name + " 데이터 쉐어링 지원 여부";
    }

    private boolean hasIndependentSentenceSupport(String sentence, AnswerMaterials materials, List<String> sources) {
        String inspected = inspect(sentence);
        if (QUESTION_END.matcher(inspected).find()) return true;
        Matcher dependent = DEPENDENT_SENTENCE.matcher(inspected);
        String claim = (dependent.find() ? inspected.substring(dependent.end()) : inspected)
                .replaceFirst("^[,，\\s]+", "").replaceFirst("[.!。！]+$", "").strip();
        String normalized = claim.replaceAll("\\s+", "");
        // 다른 문장의 인용 일부를 근거로 삼지 않고, 실제 제공한 독립 문장과 비교합니다.
        if (sources.stream().anyMatch(source -> source.replaceFirst("[.!。！]+$", "").strip()
                .replaceAll("\\s+", "").equals(normalized))) return true;
        String documents = documentPreparationContents(claim);
        if (documents != null) {
            String scope = materials.question() + " " + claim;
            List<String> scopedSources = new ArrayList<>();
            materials.faqs().stream().filter(faq -> StringUtils.hasText(faq.answer()))
                    .filter(faq -> hasSameRequirementScope(scope, faq.question() + " " + faq.answer()))
                    .forEach(faq -> scopedSources.add(inspect(faq.answer())));
            materials.sections().stream().filter(section -> hasSameRequirementScope(scope, section.title() + " " + section.content()))
                    .forEach(section -> scopedSources.add(inspect(section.content())));
            List<String> scopedSentences = scopedSources.stream().flatMap(text -> splitSentences(text).stream())
                    .map(this::inspect).toList();
            if (sources.stream().filter(scopedSentences::contains)
                    .anyMatch(source -> documents.equals(documentPreparationContents(source)))) return true;
        }
        // 미확인 금액·시간 등이 문의 안내에 섞여 복구되지 않도록 수치가 없는 일반 안내만 허용합니다.
        return inspected.codePoints().noneMatch(Character::isDigit)
                && (INDEPENDENT_CHECK_GUIDANCE.matcher(claim).find() || UNCONFIRMED_QUANTITY.matcher(claim).find());
    }

    private boolean hasIndependentQueryFact(String sentence, AnswerMaterials materials, List<String> sources) {
        if (hasIndependentSentenceSupport(sentence, materials, sources)) return true;
        String inspected = inspect(sentence);
        // 단독 완납 가능 안내는 기존 완납 방법 근거로 확인하되 다른 처리의 선행 조건으로 확대하지 않습니다.
        if (inspected.matches("^" + PAYOFF_ACTION + "(?:은|는|이|가)?\\s*가능(?:합니다|해요)[.!。！]?$")
                && sources.stream().anyMatch(source -> {
                    Matcher guidance = PAYOFF_GUIDANCE.matcher(source);
                    if (!guidance.find() || STOP_PAYOFF_LINK.matcher(source).find()
                            || isUnassertedScope(source.substring(guidance.end()))) return false;
                    String before = source.substring(0, guidance.start());
                    return !CONDITION.matcher(before).find() && !before.matches(".*(?:[0-9]|후|부터|이면|라면|해야).*");
                })) return true;
        Matcher frequency = REQUIREMENT_FREQUENCY.matcher(inspected);
        if (frequency.find()) {
            String tail = inspected.substring(frequency.end());
            Matcher permission = FREQUENCY_PERMISSION.matcher(tail);
            if (permission.find() && tail.substring(permission.end()).matches("[\\s.!。！]*")) {
                List<FrequencyClaim> claims = readFrequencyClaims(inspected, materials.question());
                List<FrequencyClaim> evidence = readFrequencyEvidence(materials, sources);
                if (claims.size() == 1 && evidence.stream().anyMatch(claims.getFirst()::matches)) return true;
            }
        }
        Matcher lockout = QUERY_LOCKOUT_NOTICE.matcher(inspected);
        if (!lockout.matches()) return false;
        String subject = lockoutSubject(sentence, materials.question());
        if (subject == null) return false;
        return materials.faqs().stream().anyMatch(faq -> hasLockoutEvidence(lockout, subject,
                faq.answer(), faq.question(), sources))
                || materials.sections().stream().anyMatch(section -> hasLockoutEvidence(lockout, subject,
                        section.content(), section.title(), sources));
    }

    private boolean hasLockoutEvidence(Matcher claim, String subject, String text, String question, List<String> sources) {
        if (!StringUtils.hasText(text)) return false;
        for (String sentence : availabilitySentences(text)) {
            String inspected = inspect(sentence);
            if (sources.stream().noneMatch(source -> source.contains(inspected))
                    || !subject.equals(lockoutSubject(inspected, question))) continue;
            Matcher source = QUERY_LOCKOUT_NOTICE.matcher(inspected);
            if (source.matches() && claim.group("unit").equals(source.group("unit"))
                    && new BigDecimal(claim.group("attempts")).equals(new BigDecimal(source.group("attempts")))
                    && new BigDecimal(claim.group("duration")).equals(new BigDecimal(source.group("duration")))
                    && (claim.group("series") == null && claim.group("prefixSeries") == null)
                    == (source.group("series") == null && source.group("prefixSeries") == null)) return true;
        }
        return false;
    }

    private String lockoutSubject(String sentence, String question) {
        Matcher stated = QUERY_LOCKOUT_SUBJECT.matcher(sentence);
        String kind = null;
        if (stated.find()) {
            kind = stated.group("kind").equalsIgnoreCase("PIN") ? "PIN" : "비밀번호";
            if (stated.group("system") != null) return stated.group("system") + "|" + kind;
        }
        Matcher context = QUERY_LOCKOUT_SUBJECT.matcher(StringUtils.hasText(question) ? question : "");
        List<String> subjects = new ArrayList<>();
        while (context.find()) {
            String contextKind = context.group("kind").equalsIgnoreCase("PIN") ? "PIN" : "비밀번호";
            if (kind == null || kind.equals(contextKind)) {
                subjects.add((context.group("system") == null ? "*" : context.group("system")) + "|" + contextKind);
            }
        }
        List<String> distinct = subjects.stream().distinct().toList();
        if (!distinct.isEmpty()) return distinct.size() == 1 ? distinct.getFirst() : null;
        return kind == null ? null : "*|" + kind;
    }

    /** 준비 안내와 서류 목록의 표현만 비교합니다. 서류명·순서·선택 관계·발급 기간·명의 조건은 보존합니다. */
    private String documentPreparationContents(String sentence) {
        Matcher preparation = DOCUMENT_PREPARATION_SENTENCE.matcher(inspect(sentence));
        if (!preparation.matches()) return null;
        String contents = preparation.group(1) == null ? preparation.group(2) : preparation.group(1);
        if (!REQUIREMENT_DOCUMENT.matcher(contents).find()) return null;
        return contents.replaceAll("각자의\\s*(?=" + REQUIREMENT_DOCUMENT.pattern() + ")", "")
                .replaceAll("\\s+", "").replace("이내의", "이내");
    }

    /** 근거 검증과 위험 문장 검사 후, 사용자에게 보이는 내부 FAQ 번호만 정리합니다. */
    private String removeFaqReferences(String answer) {
        if (!FAQ_REFERENCE_MARKER.matcher(answer).find() && !FAQ_REFERENCE_INLINE.matcher(answer).find()) return answer;
        List<String> kept = new ArrayList<>();
        boolean removed = false;
        for (String sentence : splitSentences(answer)) {
            String remaining = sentence;
            for (Pattern pattern : List.of(FAQ_REFERENCE_MARKER, FAQ_REFERENCE_INLINE)) {
                Matcher reference = pattern.matcher(remaining);
                StringBuilder cleaned = new StringBuilder();
                int cursor = 0;
                while (reference.find()) {
                    if (reference.start() < cursor || (pattern == FAQ_REFERENCE_MARKER
                            && !FAQ_REFERENCE_CONTENT.matcher(inspect(reference.group(1))).matches())) continue;
                    cleaned.append(remaining, cursor, reference.start());
                    int end = reference.end();
                    Matcher source = FAQ_REFERENCE_SOURCE.matcher(remaining.substring(end));
                    if (source.find()) {
                        // '에 따르면' 등 번호에 붙은 출처 표현만 지우고 뒤의 설명은 보존합니다.
                        end += source.end();
                    } else if (FAQ_REFERENCE_PARTICLE.matcher(remaining.substring(end)).find()) {
                        // 실제 설명에 붙은 조사는 일반 FAQ로 이어 문법과 수치를 보존합니다.
                        cleaned.append("FAQ");
                    }
                    cursor = end;
                }
                if (cursor > 0) {
                    cleaned.append(remaining, cursor, remaining.length());
                    remaining = cleaned.toString();
                }
            }
            if (remaining.equals(sentence)) {
                kept.add(sentence);
                continue;
            }
            removed = true;
            remaining = remaining.replaceAll("[\\[［(（]\\s*[,，:：;；]*\\s*[\\]］)）]", "").strip();
            // 금액·조건을 포함한 실제 설명은 남기고, 번호 참조만 안내하는 문장만 제거합니다.
            if (FAQ_REFERENCE_NOTICE.matcher(inspect(remaining)).matches()
                    || remaining.matches("[\\s\\p{Punct}。！？]*")) continue;
            kept.add(remaining.replaceAll("[ \\t]+([,.;:!?。！？])", "$1")
                    .replaceAll("[ \\t]{2,}", " "));
        }
        return removed ? String.join("\n", kept) : answer;
    }

    private boolean hasUnsupportedSource(String sentence, AnswerMaterials materials) {
        List<String> providedDocuments = materials.sections().stream()
                .filter(section -> StringUtils.hasText(section.title()) && StringUtils.hasText(section.content()))
                .map(section -> inspect(section.title()).toLowerCase(Locale.ROOT)).toList();
        Matcher external = EXTERNAL_SOURCE_CLAIM.matcher(sentence);
        while (external.find()) {
            String tail = sentence.substring(external.end());
            // 이용 방법·되묻기·사용 사실의 부정은 실제로 출처를 사용했다는 주장과 구분합니다.
            String reportedTail = tail.replaceFirst("^(?:했|하였)(?=다는\\s*(?:것|뜻|주장|표현))", "");
            if (SOURCE_USAGE_GUIDANCE.matcher(tail).find() || isRejectedOrReported(reportedTail)
                    || QUESTION_END.matcher(sentence).find() && !AFFIRMED_OUTPUT_ACTION.matcher(tail).find()) continue;
            String resource = external.group(1).toLowerCase(Locale.ROOT);
            // FAQ의 안내 링크만으로 외부 사이트를 실제 근거로 읽었다고 주장하지 않습니다.
            if (!hasSourceClaim(resource, providedDocuments)) return true;
        }
        Matcher document = FULL_DOCUMENT_CLAIM.matcher(sentence);
        while (document.find()) {
            if (isRejectedOrReported(sentence.substring(document.end()))) continue;
            String kind = document.group(1);
            // FAQ를 붙인 것을 별도 문서 전체를 제공한 것으로 취급하지 않습니다.
            if (!hasSourceClaim(kind, providedDocuments)) return true;
        }
        return false;
    }

    private String removeRequestedSuffix(String answer, String question) {
        if (!StringUtils.hasText(question)) return answer;
        String inspectedQuestion = inspect(question);
        Matcher request = REQUESTED_SUFFIX.matcher(inspectedQuestion);
        String filtered = answer;
        while (request.find()) {
            if (isRejectedOrReported(inspectedQuestion.substring(request.end()))) continue;
            String marker = request.group(1);
            if (inspect(filtered).equalsIgnoreCase(marker)) return "";
            // 요청한 표식이 끝에 단독으로 붙은 경우만 제거합니다. 단어 중간이나 본문 속 설명은 보존합니다.
            Pattern suffix = Pattern.compile("(?:\\s|\\\\[nr])+" + Pattern.quote(marker)
                    + "[.!?。！？]?\\s*$", Pattern.CASE_INSENSITIVE);
            filtered = suffix.matcher(filtered).replaceFirst("").stripTrailing();
        }
        return filtered;
    }

    private boolean hasPolicyOverride(String sentence) {
        Matcher override = POLICY_OVERRIDE.matcher(sentence);
        while (override.find()) {
            String prefix = sentence.substring(Math.max(0, override.start() - 48), override.start());
            if ("제한".equals(override.group(1)) && !OVERRIDE_PRETEXT.matcher(prefix).find()) continue;
            // 기기 설정 안내를 모델 안전 정책 변경과 혼동하지 않습니다. 명시된 모델 지시는 제외하지 않습니다.
            if (DEVICE_FILTER_CONTEXT.matcher(prefix).find() && !BOT_POLICY_CONTEXT.matcher(prefix).find()) continue;
            String tail = sentence.substring(override.end());
            if (POLICY_ACTION_COMMITMENT.matcher(tail).find() && !isRejectedOrReported(tail)) return true;
        }
        for (Pattern pattern : List.of(USER_COMMAND_AUTHORITY_CLAIM, INTERNAL_APPROVAL_CLAIM)) {
            Matcher claim = pattern.matcher(sentence);
            while (claim.find()) {
                if (!isRejectedOrReported(sentence.substring(claim.end()))) return true;
            }
        }
        Matcher compliance = UNCONDITIONAL_COMPLIANCE.matcher(sentence);
        while (compliance.find()) {
            if (!isRejectedOrReported(sentence.substring(compliance.end()))) return true;
        }
        return false;
    }

    private boolean hasThirdPartyLookup(String sentence) {
        Matcher lookup = THIRD_PARTY_LOOKUP.matcher(sentence);
        while (lookup.find()) {
            String tail = sentence.substring(lookup.end());
            // 위치를 사용자가 알려주면 매장을 설명하는 안내와, 타인의 정보를 찾아주겠다는 약속은 다릅니다.
            if (!USER_PROVIDED_ACTION.matcher(tail).find() && !isRejectedOrReported(tail)) return true;
        }
        return false;
    }

    private boolean hasHostileContent(String sentence, List<String> sources) {
        Matcher insult = PERSON_INSULT.matcher(sentence);
        while (insult.find()) {
            String tail = sentence.substring(insult.end());
            if (!isRejectedOrReported(tail) && !INSULT_REPORT.matcher(tail).find()) return true;
        }
        Matcher rebuke = QUESTION_REBUKE.matcher(sentence);
        while (rebuke.find()) {
            if (!isRejectedOrReported(sentence.substring(rebuke.end()))) return true;
        }
        Matcher restriction = GROUP_RESTRICTION.matcher(sentence);
        while (restriction.find()) {
            String tail = sentence.substring(restriction.end());
            // 부정적인 자격 제한을 다시 부정한 설명만 예외로 봅니다. 이용 불가라는 결론은 제한 철회가 아닙니다.
            if (DENIED_ASSERTION.matcher(tail).find() || QUOTED_SECRET_REQUEST.matcher(tail).find()
                    || REPORTED_OR_HYPOTHETICAL.matcher(tail).find()) continue;
            // 자료에 없는 집단 배제를 만들지 않습니다. 실제 자료의 조건까지 같은 안내만 보존합니다.
            if (!hasSourceClaim(restriction.group(), sources)) return true;
        }
        return false;
    }

    private boolean isRejectedOrReported(String tail) {
        String local = tail.stripLeading().startsWith("하거나")
                ? tail.replaceFirst("^\\s*하거나\\s*,?\\s*", "").split("[.!?。！？\\n]", 2)[0]
                : tail.split("[,;.!?。！？\\n]", 2)[0];
        if (DENIED_ASSERTION.matcher(local).find() || QUOTED_SECRET_REQUEST.matcher(local).find()
                || REPORTED_OR_HYPOTHETICAL.matcher(local).find()) return true;
        if (AFFIRMED_OUTPUT_ACTION.matcher(local).find()) return false;
        return SECRET_REFUSAL.matcher(local).find() || OUTPUT_REJECTION.matcher(local).find();
    }

    /** 인접한 내부 필드만 정리합니다. 코드 예제·인용·형식 설명은 보존합니다. */
    private String removeResponseMetadata(String answer, String question) {
        // 필드 이름 하나가 아니라 같은 객체의 상태·근거·본문 조합을 검사합니다.
        Matcher object = RESPONSE_METADATA_OBJECT.matcher(answer);
        while (object.find()) {
            String candidate = object.group();
            if (RESPONSE_STATUS_MEMBER.matcher(candidate).find()
                    && RESPONSE_EVIDENCE_MEMBER.matcher(candidate).find()
                    && RESPONSE_ANSWER_MEMBER.matcher(candidate).find()
                    && !isResponseMetadataExample(answer, object.start(), question)) return "";
        }
        Matcher metadata = RESPONSE_METADATA_BLOCK.matcher(answer);
        StringBuilder cleaned = new StringBuilder();
        int cursor = 0;
        while (metadata.find()) {
            if (isResponseMetadataExample(answer, metadata.start(), question)) continue;
            cleaned.append(answer, cursor, metadata.start());
            cursor = metadata.end();
        }
        if (cursor == 0) return answer;
        return cleaned.append(answer, cursor, answer.length()).toString().strip();
    }

    private boolean isResponseMetadataExample(String answer, int start, String question) {
        String prefix = answer.substring(0, start);
        if ((prefix.split("```", -1).length - 1) % 2 != 0) return true;
        String preceding = prefix.stripTrailing();
        String lastLine = preceding.substring(preceding.lastIndexOf('\n') + 1);
        return RESPONSE_EXAMPLE_CONTEXT.matcher(lastLine).find()
                || StringUtils.hasText(question) && RESPONSE_EXAMPLE_REQUEST.matcher(question).matches();
    }

    private List<String> internalDisclosureBlocks(String answer) {
        List<String> blocks = new ArrayList<>();
        int position = 0;
        int coveredUntil = 0;
        for (String sentence : splitSentences(answer)) {
            int start = answer.indexOf(sentence, position);
            position = start + sentence.length();
            if (start < coveredUntil || !INTERNAL_DISCLOSURE.matcher(inspect(sentence)).find()) continue;
            Matcher header = INTERNAL_DISCLOSURE.matcher(sentence);
            int end = answer.length();
            if (header.find()) {
                int body = start + header.end();
                while (body < answer.length() && Character.isWhitespace(answer.charAt(body))) body++;
                if (answer.startsWith("```", body)) {
                    int close = answer.indexOf("```", body + 3);
                    if (close >= 0) end = close + 3;
                } else if (body < answer.length() && "'\"‘“".indexOf(answer.charAt(body)) >= 0) {
                    char quote = answer.charAt(body);
                    char closing = quote == '‘' ? '’' : quote == '“' ? '”' : quote;
                    // 인용 내부의 짧은 따옴표에서 끊지 않고, 문단 끝의 닫는 따옴표까지 제거합니다.
                    Matcher close = Pattern.compile(Pattern.quote(String.valueOf(closing))
                            + "[.!?。！？]?\\s*(?=\\R\\s*\\R|$)").matcher(answer);
                    if (close.find(body + 1)) end = close.end();
                }
            }
            // 끝 경계가 불명확하면 남은 부분을 공개하지 않습니다. 앞의 독립된 상담 답변은 보존합니다.
            blocks.add(answer.substring(start, end));
            coveredUntil = end;
        }
        Matcher key = PRIVATE_KEY_BLOCK.matcher(answer);
        while (key.find()) blocks.add(key.group());
        return blocks;
    }

    private boolean hasSensitiveDisclosure(String sentence) {
        if (AUTH_TOKEN_VALUE.matcher(sentence).find()) return true;
        Matcher value = SECRET_VALUE.matcher(sentence);
        while (value.find()) {
            // 마스킹된 예시는 보존하지만, 거절 문장에 실제 값을 붙인 경우도 제거합니다.
            if (!MASKED_SECRET_VALUE.matcher(value.group(1)).matches()) return true;
        }
        Matcher target = SECRET_TARGET.matcher(sentence);
        while (target.find()) {
            String tail = sentence.substring(target.end()).split("[,;.!?。！？\\n]", 2)[0];
            if (SECRET_USAGE_QUESTION.matcher(tail).find()) continue;
            Matcher action = SECRET_ACTION.matcher(tail);
            while (action.find()) {
                String after = tail.substring(action.end());
                if (!SECRET_REFUSAL.matcher(after).find() && !QUOTED_SECRET_REQUEST.matcher(after).find()) return true;
            }
        }
        return false;
    }

    private List<String> readSources(AnswerMaterials materials) {
        List<String> sources = new ArrayList<>();
        materials.faqs().forEach(faq -> sources.add(faq.answer()));
        materials.sections().forEach(section -> sources.add(section.content()));
        // 검사 근거의 복사본만 정리합니다. 검색 문서의 모델 지시는 혜택·서류 예외의 근거가 아닙니다.
        return sources.stream().filter(StringUtils::hasText).flatMap(source -> splitSentences(source).stream())
                .map(this::inspect).filter(source -> !SOURCE_DIRECTIVE.matcher(source).find()).toList();
    }

    /** 모델에 제공된 문장 경계를 그대로 재사용하며 다른 FAQ의 조건을 가져오지 않습니다. */
    private List<String> providedSourceSentences(String text, List<String> sources) {
        if (!StringUtils.hasText(text)) return List.of();
        String normalized = inspect(text);
        return sources.stream().filter(normalized::contains).toList();
    }

    private boolean hasSourceClaim(String claim, List<String> sources) {
        if (!StringUtils.hasText(claim)) return false;
        String normalized = claim.replaceAll("\\s+", "");
        return sources.stream().anyMatch(source -> source.replaceAll("\\s+", "").contains(normalized));
    }

    private boolean hasUnsupportedFreeBenefit(String sentence, List<String> sources) {
        Matcher zero = ZERO_AFTER_CHARGE.matcher(sentence);
        while (zero.find()) {
            String tail = sentence.substring(zero.end());
            if (isDerivedQuantity(sentence, zero.start(), zero.end()) || QUALIFIED_NUMBER.matcher(tail).lookingAt()
                    || isRejectedOrReported(tail) || UNCONFIRMED_QUANTITY.matcher(tail).find()) continue;
            if (!hasSourceClaim(zero.group(), sources)) return true;
        }
        if (!hasFreeBenefit(sentence)) return false;
        // 특정 무료 항목을 모든 서비스가 무료라는 근거로 확대하지 않습니다.
        if (UNIVERSAL_BENEFIT.matcher(sentence).find()) {
            return sources.stream().noneMatch(source -> UNIVERSAL_BENEFIT.matcher(source).find() && hasFreeBenefit(source));
        }
        return sources.stream().noneMatch(source -> hasFreeBenefit(source) || INCLUDED_BENEFIT.matcher(source).find()
                || readFacts(source).stream().anyMatch(fact -> "원".equals(fact.unit()) && fact.value().signum() == 0));
    }

    private boolean hasFreeBenefit(String sentence) {
        if (QUESTION_END.matcher(sentence).find()) return false;
        Matcher benefit = FREE_BENEFIT.matcher(sentence);
        while (benefit.find()) {
            String tail = sentence.substring(benefit.end());
            if (FREE_DECLARATION.matcher(tail).find() && !QUALIFIED_NUMBER.matcher(tail).lookingAt()
                    && !CONDITIONAL_BENEFIT.matcher(tail).find()
                    && !DENIED_BENEFIT.matcher(tail).find()
                    && !UNCONFIRMED_QUANTITY.matcher(tail).find()) return true;
        }
        return false;
    }

    private boolean containsForbiddenWord(String sentence) {
        try {
            forbiddenWordFilterService.validateForbiddenWord(sentence);
            return false;
        } catch (GlobalException exception) {
            if (exception.getErrorCode() == ForbiddenWordErrorCode.FORBIDDEN_WORD_DETECTED) {
                return true;
            }
            throw exception;
        }
    }

    private boolean hasQuantityConflict(String sentence, List<QuantityFact> sources, String question,
            List<ScopedQuantity> moneyEvidence) {
        Matcher rewrite = PRICE_REWRITE_ACCEPTED.matcher(sentence);
        while (rewrite.find()) {
            if (!isRejectedOrReported(sentence.substring(rewrite.end()))) return true;
        }
        // 질문·계산 예시의 숫자를 단정으로 보지 않습니다. 동일 항목·단위에 알려진 값이 있을 때만 비교합니다.
        if (QUESTION_END.matcher(sentence).find()) return false;
        // 실제 금액을 바꾸라는 요청은 계산·가정으로 포장해도 허용하지 않습니다.
        if (StringUtils.hasText(question) && DERIVED_OR_EXAMPLE.matcher(sentence).find()
                && QUANTITY.matcher(sentence).find()) {
            String inspectedQuestion = inspect(question);
            Matcher request = REQUESTED_PRICE_REWRITE.matcher(inspectedQuestion);
            while (request.find()) {
                if (!isRejectedOrReported(inspectedQuestion.substring(request.end()))) return true;
            }
        }
        List<ScopedQuantity> moneyClaims = readMoneyFacts(sentence, question, true);
        List<QuantityFact> comparedMoney = new ArrayList<>();
        for (ScopedQuantity claim : moneyClaims) {
            List<ScopedQuantity> sameTarget = moneyEvidence.stream()
                    .filter(source -> source.target().equals(claim.target())).toList();
            if (sameTarget.isEmpty() && claim.target().equals("*")) sameTarget = moneyEvidence.stream()
                    .filter(source -> source.target().equals("*")).toList();
            List<ScopedQuantity> sameCondition = sameTarget.stream()
                    .filter(source -> source.condition().equals(claim.condition())).toList();
            if (sameCondition.isEmpty()) sameCondition = sameTarget.stream()
                    .filter(source -> source.condition().isEmpty()).toList();
            List<ScopedQuantity> comparable = sameCondition.stream()
                    .filter(source -> sameQuantityMetric(source.quantity(), claim.quantity())).toList();
            if (comparable.isEmpty() && !claim.condition().isEmpty() && sameTarget.stream()
                    .anyMatch(source -> !source.condition().isEmpty()
                            && sameQuantityMetric(source.quantity(), claim.quantity()))) return true;
            if (!comparable.isEmpty() && comparable.stream()
                    .noneMatch(source -> source.quantity().accepts(claim.quantity().value()))) return true;
            // 명시한 다른 상품의 금액과 섞지 않습니다. 조건을 확정할 수 없으면 의미상 정오를 추측하지 않습니다.
            if (!claim.target().equals("*") || !comparable.isEmpty()) comparedMoney.add(claim.quantity());
        }
        for (QuantityFact claim : readFacts(sentence, true)) {
            if (comparedMoney.stream().anyMatch(value -> value.unit().equals(claim.unit())
                    && value.value().compareTo(claim.value()) == 0)) continue;
            // 요금제 선택 조건의 GB는 요금 금액이 아닙니다. 실제 데이터 제공량 주장은 기존 검사로 봅니다.
            if (!moneyClaims.isEmpty() && claim.metric().equals("요금") && claim.unit().matches("gb|mb")) continue;
            // 서류 발급 기간은 아래 서류 검사에서 대상·신청 조건까지 함께 비교합니다.
            if (claim.metric().startsWith("서류발급기간|")) continue;
            List<QuantityFact> sameMetric = sources.stream()
                    .filter(source -> (source.metric().equals(claim.metric()) || claim.metric().equals("요금")
                            && source.metric().equals("할인후요금") && claim.unit().equals("원"))
                            && source.unit().equals(claim.unit())).toList();
            if (!sameMetric.isEmpty() && sameMetric.stream().noneMatch(source -> source.accepts(claim.value()))) {
                return true;
            }
        }
        return false;
    }


    /** 기존 수치 파서를 재사용하고, 금액이 붙은 목록의 대상·선택 조건만 함께 보관합니다. */
    private List<ScopedQuantity> readMoneyEvidence(AnswerMaterials materials, List<String> sources) {
        List<ScopedQuantity> evidence = new ArrayList<>();
        materials.faqs().forEach(faq -> providedSourceSentences(faq.answer(), sources).forEach(sentence ->
                evidence.addAll(readMoneyFacts(sentence, faq.question(), false))));
        materials.sections().forEach(section -> providedSourceSentences(section.content(), sources).forEach(sentence ->
                evidence.addAll(readMoneyFacts(sentence, section.title(), false))));
        return evidence;
    }

    private boolean sameQuantityMetric(QuantityFact source, QuantityFact claim) {
        return source.unit().equals(claim.unit()) && (source.metric().equals(claim.metric())
                || claim.metric().equals("요금") && source.metric().equals("할인후요금"));
    }

    private List<ScopedQuantity> readMoneyFacts(String text, String question, boolean claim) {
        List<ScopedQuantity> result = new ArrayList<>();
        List<String> sharedMetrics = METRIC.matcher(text).results().map(match -> match.group().replaceAll("\\s+", ""))
                .map(value -> switch (value) {
                    case "월요금", "월정액" -> "요금";
                    case "할인율", "할인금액" -> "할인";
                    case "환불율", "환불금액" -> "환불";
                    default -> value;
                }).filter(value -> value.matches("요금|할인|할인후요금|환불|수수료|위약금|배송비"))
                .distinct().toList();
        Matcher amounts = QUANTITY.matcher(text);
        int previousEnd = 0;
        while (amounts.find()) {
            if (amounts.group("money") == null) continue;
            String after = text.substring(amounts.end());
            if (claim && isDerivedQuantity(text, amounts.start(), amounts.end())
                    || QUALIFIED_NUMBER.matcher(after).lookingAt() || UNCONFIRMED_QUANTITY.matcher(after).find()) continue;
            String local = readQuantityClause(text, amounts.start(), amounts.end());
            String prefix = text.substring(previousEnd, amounts.start());
            previousEnd = amounts.end();
            String target = moneyTarget(local, question);
            if (target.equals("*")) target = moneyTarget(text, question);
            String condition = moneyCondition(prefix);
            if (condition.isEmpty() && claim && StringUtils.hasText(question)) condition = singleMoneyCondition(question);
            BigDecimal value = readWonValue(amounts.group("money"));
            if (value == null) continue;
            List<QuantityFact> localFacts = readFacts(local).stream().filter(fact -> fact.unit().equals("원")
                    && fact.value().compareTo(value) == 0).toList();
            boolean listed = !condition.isEmpty();
            String metric = localFacts.size() == 1 ? localFacts.getFirst().metric()
                    : sharedMetrics.size() == 1 && listed ? sharedMetrics.getFirst()
                    : prefix.matches("(?s).*월\\s*$") || listed ? "요금" : null;
            if (metric == null) continue;
            String normalizedAmount = metric + " " + amounts.group("money")
                    + after.substring(0, Math.min(after.length(), 8));
            List<QuantityFact> parsed = readFacts(normalizedAmount);
            if (!parsed.isEmpty()) result.add(new ScopedQuantity(localFacts.size() == 1
                    ? localFacts.getFirst() : parsed.getFirst(), target, condition));
        }
        return result;
    }

    private String moneyTarget(String text, String question) {
        List<String> services = SERVICE_SCOPE.matcher(text).results().map(match -> serviceName(match.group()))
                .distinct().toList();
        if (services.size() == 1) return services.getFirst().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        int end = text.length();
        Matcher metric = PLAN_PRICE_LABEL.matcher(text);
        if (metric.find()) end = Math.min(end, metric.start());
        Matcher selector = MONEY_SELECTOR.matcher(text);
        if (selector.find()) end = Math.min(end, selector.start());
        Matcher amount = QUANTITY.matcher(text);
        if (amount.find()) end = Math.min(end, amount.start());
        String prefix = text.substring(0, end).replaceFirst("^[\\s,，;；)]+", "")
                .replaceFirst("(?:요금제)?(?:의|은|는|이|가)?\\s*$", "").strip();
        String subject = planPriceSubject(prefix);
        if (CONDITION_SCOPE_MARKER.matcher(prefix).matches()) subject = null;
        if (subject != null && subject.matches(".*(?:얼마|어떻게|알려|확인|이번달|고객님|회원님).*")) subject = null;
        if (StringUtils.hasText(question) && !question.equals(text)
                && !question.matches("(?s).*(?:과|와|및|또는|각각|[/·]).*")
                && (METRIC.matcher(question).find() || MONEY_SELECTOR.matcher(question).find()
                || SERVICE_SCOPE.matcher(question).find())) {
            String named = moneyTarget(question, null);
            if (subject == null) return named;
            // 문서의 브랜드 수식어만 생략한 동일 이름을 FAQ 자체 질문으로 확인합니다.
            if (!named.equals("*") && prefix.toLowerCase(Locale.ROOT).endsWith(" " + named)) return named;
        }
        return subject == null ? "*" : subject;
    }

    private String moneyCondition(String context) {
        Matcher selector = MONEY_SELECTOR.matcher(context);
        String selected = "";
        while (selector.find()) {
            BigDecimal value = new BigDecimal(selector.group(1));
            String unit = selector.group(2).toLowerCase(Locale.ROOT);
            if (unit.equals("gb")) { value = value.multiply(BigDecimal.valueOf(1024)); unit = "mb"; }
            if (unit.equals("gbps")) { value = value.multiply(BigDecimal.valueOf(1000)); unit = "mbps"; }
            selected = value.stripTrailingZeros().toPlainString() + unit;
        }
        List<String> qualifiers = CONDITION_SCOPE_MARKER.matcher(context).results().map(match -> match.group())
                .filter(value -> value.matches("미성년자|성인|법인|선불|후불|대리인|명의자|타인|법정대리인|보호자|본인"
                        + "|온라인|매장|국내|해외|평일|주말|공휴일|[월화수목금토일]요일"))
                .distinct().sorted().toList();
        return selected + String.join("", qualifiers);
    }

    private String singleMoneyCondition(String context) {
        return MONEY_SELECTOR.matcher(context).results().count() == 1 ? moneyCondition(context) : "";
    }


    /** 계산·가정이 적용되는 절의 숫자만 예외로 보고, 별도 확정 주장까지 제외하지 않습니다. */
    private boolean isDerivedQuantity(String sentence, int quantityStart, int quantityEnd) {
        String clause = readQuantityClause(sentence, quantityStart, quantityEnd);
        Matcher derived = DERIVED_OR_EXAMPLE.matcher(clause);
        while (derived.find()) {
            String tail = clause.substring(derived.end());
            if (QUALIFIED_NUMBER.matcher(tail).lookingAt() || isRejectedOrReported(tail)) continue;
            // '할인 후 요금'은 금액 종류입니다. 할인율을 적용하는 계산과 구분합니다.
            if (derived.group().matches("(?:할인|적용)\\s*후")
                    && !clause.substring(0, derived.start()).matches("(?s).*[0-9.,]+\\s*(?:%|퍼센트).*")) continue;
            return true;
        }
        return false;
    }

    private String readQuantityClause(String sentence, int quantityStart, int quantityEnd) {
        int start = 0;
        int end = sentence.length();
        Matcher boundary = QUANTITY_CLAUSE_BOUNDARY.matcher(sentence);
        while (boundary.find()) {
            if (boundary.end() <= quantityStart) {
                String previous = sentence.substring(start, boundary.start()).strip();
                String next = sentence.substring(boundary.end()).stripLeading();
                boolean continuingExample = DERIVED_OR_EXAMPLE.matcher(previous).find()
                        && !ASSERTED_CLAUSE_END.matcher(previous).find()
                        && !ASSERTED_CLAUSE_END.matcher(boundary.group().strip()).find();
                if (!continuingExample || next.matches("^(?:하지만|반면|다만|실제|정확한|원래)[,，\\s].*")) {
                    start = boundary.end();
                }
            } else if (boundary.start() >= quantityEnd
                    || boundary.start() >= quantityStart && boundary.end() >= quantityEnd) {
                end = boundary.start();
                break;
            }
        }
        return sentence.substring(start, end);
    }

    private List<QuantityFact> readFacts(String text) {
        return readFacts(text, false);
    }

    private List<QuantityFact> readFacts(String text, boolean skipDerived) {
        List<QuantityFact> facts = new ArrayList<>();
        Matcher quantity = QUANTITY.matcher(text);
        while (quantity.find()) {
            String money = quantity.group("money");
            BigDecimal value = money == null
                    ? new BigDecimal(quantity.group("value").replace(",", "")) : readWonValue(money);
            if (value == null) continue;
            String unit = money == null ? quantity.group("unit").toLowerCase(Locale.ROOT) : "원";
            if (skipDerived && isDerivedQuantity(text, quantity.start(), quantity.end())) continue;
            if (QUALIFIED_NUMBER.matcher(text.substring(quantity.end())).lookingAt()) continue;
            // 불확실한 값·명확히 잘못됐다고 정정한 인용 수치는 확정값으로 보지 않습니다. 다음 숫자나 절 경계를 넘지 않습니다.
            if (UNCONFIRMED_QUANTITY.matcher(text.substring(quantity.end())).find()) continue;
            String metric = null;
            int clauseStart = Math.max(0, text.lastIndexOf('\n', quantity.start()) + 1);
            Matcher label = METRIC.matcher(text).region(clauseStart, quantity.start());
            while (label.find()) {
                if (quantity.start() - label.end() <= 16) metric = label.group().replaceAll("\\s+", "");
            }
            if (metric == null) {
                Matcher after = METRIC.matcher(text).region(quantity.end(), text.length());
                if (after.find() && after.start() - quantity.end() <= 3) metric = after.group().replaceAll("\\s+", "");
            }
            // 금액 종류의 구분을 보완하고, 기존 숫자·단위·부정 처리는 그대로 재사용합니다.
            if (money != null) {
                Matcher price = PLAN_PRICE_LABEL.matcher(text).region(clauseStart, quantity.start());
                String priceLabel = null;
                int priceStart = -1;
                while (price.find()) {
                    if (quantity.start() - price.end() <= 12) {
                        priceLabel = price.group();
                        priceStart = price.start();
                    }
                }
                if (priceLabel != null) {
                    String priceMetric = planPriceMetric(priceLabel,
                            text.substring(clauseStart, priceStart), text.substring(quantity.end()));
                    if (priceMetric.equals("할인후요금")) metric = priceMetric;
                }
            }
            String documentMetric = documentIssuanceMetric(text, quantity.start(), quantity.end());
            if (documentMetric != null) metric = documentMetric;
            if (metric == null) continue;
            metric = switch (metric) {
                case "월요금", "월정액" -> "요금";
                case "할인율", "할인금액" -> "할인";
                case "환불율", "환불금액" -> "환불";
                case "데이터제공량", "데이터용량" -> "데이터";
                case "건당" -> "건당요금";
                default -> metric;
            };
            switch (unit) {
                case "년" -> { value = value.multiply(BigDecimal.valueOf(12)); unit = "개월"; }
                case "퍼센트" -> unit = "%";
                case "km" -> { value = value.multiply(BigDecimal.valueOf(1000)); unit = "m"; }
                case "기가바이트" -> unit = "gb";
                case "메가바이트" -> unit = "mb";
                default -> { }
            }
            String before = text.substring(Math.max(0, quantity.start() - 8), quantity.start());
            String after = text.substring(quantity.end(), Math.min(text.length(), quantity.end() + 8));
            // 상한·하한을 고정값으로 잘못 비교하지 않습니다. 근삿값·복합 범위는 이 단계에서 단정하지 않습니다.
            QuantityBound bound = UNCERTAIN_AMOUNT.matcher(before).find() || UNCERTAIN_AMOUNT.matcher(after).find()
                    ? QuantityBound.UNCERTAIN
                    : before.contains("최대") || after.matches("^\\s*(?:이하|이내|까지).*") ? QuantityBound.MAXIMUM
                    : before.contains("최소") || after.matches("^\\s*(?:이상|부터).*") ? QuantityBound.MINIMUM : QuantityBound.EXACT;
            facts.add(new QuantityFact(metric, value, unit, bound));
        }
        return facts;
    }

    /** 발급 기한에 직접 연결된 서류명만 읽습니다. 약정 기간이나 다른 서류의 기간은 합치지 않습니다. */
    private String documentIssuanceMetric(String text, int start, int end) {
        Matcher period = DOCUMENT_ISSUANCE_PERIOD.matcher(text);
        while (period.find()) {
            if (period.start() > start || period.end() < end) continue;
            String clause = readQuantityClause(text, period.start(), period.end());
            String attribution = clause.replaceFirst("(?s)^.*?(?=하다는|이라는|라는)", "")
                    .replaceFirst("^하다는", "라는");
            if (UNCONFIRMED_QUANTITY.matcher(attribution).find()
                    || isUnassertedRequirement(text.substring(period.end()))) continue;
            int offset = text.lastIndexOf(clause, period.start());
            if (offset < 0) continue;
            Matcher documents = DOCUMENT_PERIOD_DOCUMENT.matcher(clause);
            List<String> names = new ArrayList<>();
            int nearest = Integer.MAX_VALUE;
            while (documents.find()) {
                int documentStart = offset + documents.start();
                int documentEnd = offset + documents.end();
                String gap = documentEnd <= period.start() ? text.substring(documentEnd, period.start())
                        : documentStart >= period.end() ? text.substring(period.end(), documentStart) : null;
                if (gap == null || DOCUMENT_ISSUANCE_PERIOD.matcher(gap).find()) continue;
                int distance = gap.replaceAll("\\s+", "").length();
                if (distance > 24 || distance > nearest) continue;
                if (distance < nearest) { names.clear(); nearest = distance; }
                names.add(documentName(documents.group()));
            }
            List<String> distinct = names.stream().distinct().toList();
            if (distinct.size() == 1) return "서류발급기간|" + distinct.getFirst();
        }
        return null;
    }

    private boolean hasDocumentPeriodConflict(String sentence, AnswerMaterials materials, List<String> sources) {
        if (QUESTION_END.matcher(sentence).find()) return false;
        List<QuantityFact> claims = readFacts(sentence, true).stream()
                .filter(fact -> fact.metric().startsWith("서류발급기간|")).toList();
        if (claims.isEmpty()) return false;
        List<QuantityFact> evidence = new ArrayList<>();
        String scope = materials.question() + " " + sentence;
        materials.faqs().stream().filter(faq -> hasSameRequirementScope(scope, faq.question() + " " + faq.answer()))
                .forEach(faq -> sources.stream().filter(source -> inspect(faq.answer()).contains(source))
                        .forEach(source -> evidence.addAll(readFacts(source))));
        materials.sections().stream().filter(section -> hasSameRequirementScope(scope, section.title() + " " + section.content()))
                .forEach(section -> sources.stream().filter(source -> inspect(section.content()).contains(source))
                        .forEach(source -> evidence.addAll(readFacts(source))));
        // 기한 자체를 바꾸는 단정이므로 상한 이내의 임의 숫자로 조건을 강화하는 것도 허용하지 않습니다.
        return claims.stream().anyMatch(claim -> evidence.stream().anyMatch(source ->
                source.metric().equals(claim.metric()) && source.unit().equals(claim.unit()))
                && evidence.stream().noneMatch(source -> source.metric().equals(claim.metric())
                && source.unit().equals(claim.unit()) && source.value().compareTo(claim.value()) == 0
                && source.bound() == claim.bound()));
    }

    /** 만·천 등을 포함한 금액 전체를 원 단위로 읽습니다. 일부 자릿수만 사실로 비교하지 않습니다. */
    private BigDecimal readWonValue(String amount) {
        Matcher component = MONEY_COMPONENT.matcher(amount);
        BigDecimal value = BigDecimal.ZERO;
        int previousUnit = Integer.MAX_VALUE;
        int end = 0;
        while (component.find()) {
            if (!amount.substring(end, component.start()).isBlank()) return null;
            int unit = component.group(2) == null ? 1 : switch (component.group(2)) {
                case "억" -> 100000000;
                case "만" -> 10000;
                case "천" -> 1000;
                case "백" -> 100;
                case "십" -> 10;
                default -> 1;
            };
            // 5만 5천원처럼 큰 단위부터 내려오는 표기만 합칩니다.
            if (unit >= previousUnit) return null;
            value = value.add(new BigDecimal(component.group(1).replace(",", ""))
                    .multiply(BigDecimal.valueOf(unit)));
            previousUnit = unit;
            end = component.end();
        }
        return end == amount.length() ? value : null;
    }

    private boolean hasRequirementConflict(String sentence, List<String> sources) {
        if (QUESTION_END.matcher(sentence).find()) return false;
        if (sentence.matches("(?s).*(?:한\\s*번|최초)\\s*(?:본인\\s*)?인증.*다시\\s*인증\\s*없이.*")
                && !hasSupportedAuthenticationWindow(sentence, sources) && !hasSourceClaim(sentence, sources)) return true;
        Matcher policy = SOURCE_POLICY_CLAIM.matcher(sentence);
        while (policy.find()) {
            String tail = sentence.substring(policy.end());
            if (QUALIFIED_NUMBER.matcher(tail).lookingAt() || isRejectedOrReported(tail)
                    || UNCONFIRMED_QUANTITY.matcher(tail).find()
                    || REPORTED_POLICY_REQUEST.matcher(tail).find()) continue;
            if (!hasSourceClaim(policy.group(), sources)) return true;
        }
        for (DocumentCondition claim : readConditions(sentence)) {
            if (!claim.waived()) continue;
            if (claim.document().equals("본인인증") && hasSupportedAuthenticationWindow(sentence, sources)) continue;
            List<DocumentCondition> conditions = sources.stream().filter(StringUtils::hasText)
                    .flatMap(source -> readConditions(inspect(source)).stream())
                    .filter(condition -> condition.document().equals(claim.document())).toList();
            // 필수 여부가 생략된 자료에서도 새로운 면제 정책을 만들어내지는 못합니다.
            if (conditions.stream().noneMatch(DocumentCondition::waived)) return true;
        }
        return false;
    }

    /** 동일 자료의 최초 인증·한시적 재인증 생략을 함께 확인합니다. 시간 제한 없는 면제는 허용하지 않습니다. */
    private boolean hasSupportedAuthenticationWindow(String sentence, List<String> sources) {
        Matcher authenticated = INITIAL_AUTHENTICATION.matcher(sentence);
        Matcher window = REAUTHENTICATION_WINDOW.matcher(sentence);
        if (!authenticated.find() || !window.find() || authenticated.end() > window.start()) return false;
        String before = sentence.substring(0, authenticated.start());
        for (String source : sources) {
            Matcher initial = SOURCE_AUTHENTICATION.matcher(source);
            if (!initial.find()) continue;
            String scope = conditionContext(source.substring(0, initial.start()))
                    .replaceAll("보려고\\s*$", "").replaceAll("\\s+", "");
            if (scope.length() < 4 || !conditionTopicKey(before).contains(scope)) continue;
            String grace = source.substring(initial.end());
            if (grace.matches("^\\s*" + window.group(1) + "\\s*" + window.group(2)
                    + "\\s*동안\\s*다시\\s*(?:인증(?:을|를)?\\s*)?(?:묻지|요구하지)\\s*않.*")) return true;
        }
        return false;
    }

    private List<DocumentCondition> readConditions(String text) {
        List<DocumentCondition> conditions = new ArrayList<>();
        Matcher documents = DOCUMENT.matcher(text);
        List<Integer> starts = new ArrayList<>();
        while (documents.find()) starts.add(documents.start());
        for (int index = 0; index < starts.size(); index++) {
            int start = starts.get(index);
            int end = index + 1 < starts.size() ? starts.get(index + 1) : text.length();
            String clause = text.substring(start, end).split("[.!?。！？\\n]", 2)[0];
            Matcher name = DOCUMENT.matcher(clause);
            if (!name.find()) continue;
            boolean waived = WAIVED.matcher(clause).find() && !DENIED_WAIVER.matcher(clause).find();
            Matcher obligation = DOCUMENT_OBLIGATION.matcher(clause.substring(name.end()));
            while (obligation.find()) {
                // 제출 의무의 부정도 기존 면제 근거 검사로 확인합니다.
                waived |= DENIED_DOCUMENT_OBLIGATION.matcher(
                        clause.substring(name.end() + obligation.end())).matches();
            }
            Matcher absence = REQUIREMENT_ABSENCE.matcher(clause.substring(name.end()));
            if ("필수조건".equals(name.group().replaceAll("\\s+", "")) && absence.find()) {
                String tail = clause.substring(name.end() + absence.end());
                waived |= !isRejectedOrReported(tail);
            }
            conditions.add(new DocumentCondition(name.group().replaceAll("\\s+", "").replace("본인확인", "본인인증"),
                    waived));
        }
        return conditions;
    }

    private boolean hasUnsupportedOperation(String sentence, AnswerMaterials materials, StoreMapResult storeMap) {
        if (QUESTION_END.matcher(sentence).find()) return false;
        if (hasActualClaim(TRANSACTION_CLAIM, withoutRecordedOperations(sentence, materials), materials.question())) return true;
        // 문장이 가리키는 청구서(월·회선)가 회원 기록에 없으면 FAQ 요금이나 기본 회원 정보로 실제 청구를 단정하지 않습니다.
        // 그 청구서가 있으면 실제 조회 결과이므로, 금액은 수치 검사가 그 청구서와 비교합니다.
        if (hasActualClaim(PERSONAL_BILLING_CLAIM, sentence) && !userRecords(materials).hasReferencedBill(sentence)) {
            return true;
        }
        if (hasUnsupportedQueryPromise(sentence, materials, storeMap)) return true;
        boolean storeProgress = STORE_SUBJECT.matcher(sentence).find() && hasActualClaim(STORE_PROGRESS_CLAIM, sentence);
        if (!hasActualClaim(QUERY_CLAIM, sentence) && !storeProgress) return false;
        if (STATIC_SOURCE.matcher(sentence).find() && !sentence.contains("조회") && !sentence.contains("검색")) return false;
        if (OTHER_QUERY_SUBJECT.matcher(sentence).find()) return true;
        if (STORE_SUBJECT.matcher(sentence).find()) return storeMap == null;
        return !USER_SUBJECT.matcher(sentence).find() || !hasUserData(materials);
    }


    /** 상품명 안의 수치는 제공량 주장이 아닙니다. 실제 기록에 있는 이름만 비교용 사본에서 가립니다. */
    private String withoutRecordedPlanNames(String sentence, AnswerMaterials materials) {
        List<String> names = new ArrayList<>();
        if (materials.userDataSummary() != null) materials.userDataSummary().subscriptions().forEach(subscription -> {
            if (subscription.plan() != null) names.add(subscription.plan().summary().name());
        });
        if (materials.toolRecords() != null) materials.toolRecords().plans().forEach(plan -> names.add(plan.plan().summary().name()));
        String result = sentence;
        for (String name : names.stream().filter(StringUtils::hasText).distinct()
                .sorted((left, right) -> Integer.compare(right.length(), left.length())).toList()) {
            result = result.replaceAll("(?<![\\p{L}\\p{N}])" + Pattern.quote(inspect(name))
                    + "(?=$|\\s|[\"'.,]|(?:은|는|을|를|의)(?=\\s|$)|(?:이며|이고|입니다|이에요))", "상품명");
        }
        return result;
    }

    /** 날짜·납부 이력과 일치하는 과거 서술만 제외합니다. 같은 문장의 새 처리 주장은 계속 검사합니다. */
    private String withoutRecordedOperations(String sentence, AnswerMaterials materials) {
        Matcher operation = TRANSACTION_CLAIM.matcher(sentence);
        StringBuilder result = new StringBuilder(sentence);
        while (operation.find()) {
            String context = sentence.substring(0, operation.start());
            String claim = operation.group();
            if (BOT_QUERY_SUBJECT.matcher(context).find() || OTHER_QUERY_SUBJECT.matcher(context).find()
                    || RECORDED_OTHER_ACTOR.matcher(context).find() || claim.contains("드렸")
                    || context.matches("(?s).*(?:방금|지금|대신|직접)\\s*.*")) continue;
            boolean recorded = false;
            var summary = materials.userDataSummary();
            if (summary != null && summary.profile() != null && claim.matches("가입\\s*했.*")) {
                LocalDate date = recordDate(context);
                recorded = date != null && date.equals(summary.profile().joinedAt())
                        && isRecordedMemberContext(context, summary.profile().name());
            }
            if (summary != null && claim.matches("납부\\s*했.*")) {
                Matcher months = INSTALLMENT_HISTORY.matcher(context.strip());
                if (months.find()) {
                    var candidates = summary.subscriptions().stream()
                            .filter(subscription -> matchesRecordedPhone(sentence, subscription.subscription().phoneNumber()))
                            .filter(subscription -> subscription.activeContract() != null
                                    && subscription.activeContract().installment() != null).toList();
                    recorded = !candidates.isEmpty() && candidates.stream().allMatch(subscription -> {
                        var installment = subscription.activeContract().installment();
                        Matcher remaining = INSTALLMENT_REMAINING.matcher(sentence.substring(operation.end()));
                        return installment.installmentMonths() != null && installment.paidInstallmentMonths() != null
                                && Integer.parseInt(months.group(1)) == installment.installmentMonths()
                                && Integer.parseInt(months.group(2)) == installment.paidInstallmentMonths()
                                && (!remaining.find() || Integer.parseInt(remaining.group(1))
                                == installment.installmentMonths() - installment.paidInstallmentMonths());
                    });
                }
            }
            if (claim.matches("(?:납부|결제).*완료(?:되|됐).*")) recorded = hasRecordedPaidBill(sentence, materials);
            if (recorded) result.replace(operation.start(), operation.end(), " ".repeat(operation.end() - operation.start()));
        }
        return result.toString();
    }

    private boolean hasRecordedPaidBill(String sentence, AnswerMaterials materials) {
        List<BillingCard> bills = new ArrayList<>();
        if (materials.userDataSummary() != null) materials.userDataSummary().subscriptions().forEach(subscription -> {
            String phone = subscription.subscription().phoneNumber();
            if (subscription.latestBilling() != null) bills.add(BillingCard.of(phone, subscription.latestBilling()));
            subscription.unpaidBillings().forEach(bill -> bills.add(BillingCard.of(phone, bill)));
        });
        if (materials.toolRecords() != null) bills.addAll(materials.toolRecords().billings());
        var matching = bills.stream().filter(card -> UserRecordClaims.of(null,
                new UserDataCards(List.of(), List.of(), List.of(card))).hasReferencedBill(sentence)).toList();
        LocalDate claimedDate = recordDate(sentence);
        if (RECORDED_DATE.matcher(sentence).find() && claimedDate == null) return false;
        return !matching.isEmpty() && matching.stream().allMatch(card -> {
            var bill = card.billing();
            return bill.paymentStatus() == PaymentStatus.PAID
                    && (claimedDate == null || bill.paidAt() != null && claimedDate.equals(bill.paidAt().toLocalDate()))
                    && (!sentence.contains("자동이체") || bill.paymentMethod() == PaymentMethod.AUTO_TRANSFER)
                    && (!sentence.contains("카드") || bill.paymentMethod() == PaymentMethod.CARD)
                    && (!sentence.contains("계좌") || bill.paymentMethod() == PaymentMethod.ACCOUNT
                            || bill.paymentMethod() == PaymentMethod.AUTO_TRANSFER);
        });
    }

    private LocalDate recordDate(String text) {
        Matcher dates = RECORDED_DATE.matcher(text);
        LocalDate date = null;
        while (dates.find()) {
            try { date = LocalDate.of(Integer.parseInt(dates.group(1)), Integer.parseInt(dates.group(2)),
                    Integer.parseInt(dates.group(3))); }
            catch (DateTimeException invalid) { return null; }
        }
        return date;
    }

    private boolean matchesRecordedPhone(String sentence, String phone) {
        Matcher claimed = RECORDED_PHONE.matcher(sentence);
        if (!claimed.find()) return true;
        String digits = claimed.group(1) == null ? claimed.group(2) : claimed.group(1);
        return phone != null && phone.replaceAll("[^0-9]", "").endsWith(digits);
    }

    private boolean isRecordedMemberContext(String context, String name) {
        if (StringUtils.hasText(name) && Pattern.compile("^" + Pattern.quote(name)
                + "(?:님)?(?:은|는|이|가|께서)?(?:\\s|$)").matcher(context).find()) return true;
        String withoutDate = RECORDED_DATE.matcher(context).replaceAll("").strip();
        return withoutDate.matches("^(?:(?:고객|회원)(?:님)?(?:은|는|이|가|께서)?\\s*)?(?:에\\s*)?(?:회원\\s*)?$");
    }

    /** 월 납부액을 청구서의 납부액으로 오인하지 않도록, 해당 회선 할부 기록과 먼저 비교합니다. */
    private UserRecordClaims.Verdict checkRecordedInstallmentAmounts(String sentence, AnswerMaterials materials) {
        if (materials.userDataSummary() == null) return new UserRecordClaims.Verdict(sentence, false);
        Matcher amount = RECORDED_INSTALLMENT_AMOUNT.matcher(sentence);
        StringBuilder result = new StringBuilder(sentence);
        boolean conflict = false;
        while (amount.find()) {
            BigDecimal value = readWonValue(amount.group("amount"));
            var installments = materials.userDataSummary().subscriptions().stream()
                    .filter(subscription -> matchesRecordedPhone(sentence, subscription.subscription().phoneNumber()))
                    .filter(subscription -> subscription.activeContract() != null
                            && subscription.activeContract().installment() != null)
                    .map(subscription -> subscription.activeContract().installment()).toList();
            List<Integer> expected = installments.stream().map(installment -> installment.monthlyInstallmentAmount())
                    .filter(Objects::nonNull).distinct().toList();
            if (value == null || expected.size() != 1) continue;
            conflict |= value.compareTo(BigDecimal.valueOf(expected.getFirst())) != 0;
            result.replace(amount.start("amount"), amount.end("amount"), " ".repeat(amount.group("amount").length()));
        }
        return new UserRecordClaims.Verdict(result.toString(), conflict);
    }


    private boolean hasUnsupportedQueryPromise(String sentence, AnswerMaterials materials, StoreMapResult storeMap) {
        Matcher promises = QUERY_PROMISE.matcher(sentence);
        while (promises.find()) {
            String tail = sentence.substring(promises.end()).stripLeading().replaceFirst("^[’”'\\\"]", "");
            String reportedTail = tail.replaceFirst("^있(?=는\\s*(?:것|게))", "");
            if (REPORTED_OR_HYPOTHETICAL.matcher(tail).find()
                    || DENIED_ASSERTION.matcher(reportedTail).find()) continue;
            String promise = promises.group();
            String prefix = sentence.substring(0, promises.start());
            Matcher quotation = QUOTED_QUERY_GUIDANCE.matcher(sentence);
            boolean quotedSource = false;
            // 번호 정리 뒤 출처 표시나 명확한 전달 표현이 남는 인용만 보존합니다.
            String sourcePrefix = inspect(removeFaqReferences(prefix));
            if (QUERY_SOURCE_REPORT.matcher(sentence).find()) {
                sourcePrefix = FAQ_REFERENCE_MARKER.matcher(prefix).replaceAll(reference -> Matcher.quoteReplacement(
                        FAQ_REFERENCE_CONTENT.matcher(inspect(reference.group(1))).matches() ? "FAQ" : reference.group()));
            }
            Matcher attribution = QUERY_SOURCE_ATTRIBUTION.matcher(sourcePrefix);
            while (attribution.find()) {
                String attributed = (sourcePrefix.substring(attribution.end()) + promise)
                        .replaceFirst("드리(?:고|며)$", "드립니다");
                quotedSource |= !BOT_QUERY_SUBJECT.matcher(attributed).find()
                        && hasSourceClaim(attributed, readSources(materials));
            }
            while (quotation.find()) {
                String quoted = quotation.group(1);
                quotedSource |= promises.start() >= quotation.start(1) && promises.end() <= quotation.end(1)
                        && STATIC_SOURCE.matcher(sentence.substring(0, quotation.start())).find()
                        && hasSourceClaim(quoted.substring(1, quoted.length() - 1), readSources(materials));
            }
            if (quotedSource) continue;
            // 앱·고객센터의 이용 가능 안내와 상담봇 본인의 실행 약속은 구분합니다.
            boolean providerDescription = promise.endsWith("있습니다") || promise.endsWith("있어요")
                    || promise.endsWith("드립니다") || promise.endsWith("드려요")
                    || promise.endsWith("제공합니다") || promise.endsWith("제공해요")
                    || promise.endsWith("드리며") || promise.endsWith("드리고");
            Matcher providers = PROVIDER_GUIDANCE.matcher(prefix);
            boolean providerGuidance = false;
            while (providers.find()) {
                // 이용자가 앱·매장에서 자료를 확인한 뒤 봇이 별도로 조회한다는 약속은 예외가 아닙니다.
                providerGuidance = !USER_QUERY_PREPARATION.matcher(prefix.substring(providers.end())).find();
            }
            if (providerDescription && providerGuidance
                    && !BOT_QUERY_SUBJECT.matcher(prefix).find()) continue;
            // 사용자가 제공할 자료의 확인은 외부 API 조회로 보지 않습니다.
            boolean externalLookup = promise.contains("조회") || promise.contains("검색") || promise.startsWith("찾아");
            Matcher provided = PROVIDED_MATERIAL.matcher(prefix);
            boolean suppliedMaterial = false;
            while (provided.find()) {
                suppliedMaterial |= !ACCOUNT_RECORD.matcher(prefix.substring(provided.end())).find();
            }
            if (!externalLookup && !ACCOUNT_RECORD.matcher(promise).find()
                    && (suppliedMaterial || MATERIAL_CONFIRMATION.matcher(prefix).find())) continue;
            String subject = sentence.substring(0, promises.end());
            if (!STORE_SUBJECT.matcher(subject).find() && !USER_SUBJECT.matcher(subject).find()) {
                subject += " " + materials.question();
            }
            // 실제 매장 검색은 개인 예약·청구·계약 내역을 조회하는 도구가 아닙니다.
            if (ACCOUNT_RECORD.matcher(subject).find()) return true;
            if (STORE_SUBJECT.matcher(subject).find()) {
                if (storeMap == null && !materials.tools().contains(AiTool.STORE_SEARCH)) return true;
            } else if (OTHER_QUERY_SUBJECT.matcher(subject).find()
                    || !USER_SUBJECT.matcher(subject).find() || !hasUserData(materials)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasOnlyQueryCondition(String sentence) {
        if (!QUERY_PROMISE.matcher(sentence).find() || hasActualClaim(QUERY_CLAIM, sentence)
                || hasActualClaim(TRANSACTION_CLAIM, sentence)
                || hasActualClaim(PERSONAL_BILLING_CLAIM, sentence)
                || DOCUMENT.matcher(sentence).find() || QUANTITY.matcher(sentence).find()) return false;
        Matcher conditions = CONDITION.matcher(sentence);
        while (conditions.find()) {
            if (!conditions.group().equals("경우") && !conditions.group().equals("때에는")) return false;
        }
        return true;
    }

    private boolean isUserDataSection(ContextSection section) {
        return UserIntentHandler.SECTION_TITLE.equals(section.title())
                || UserDataRecorder.SECTION_TITLE.equals(section.title());
    }

    /** 가입 정보 요약의 최근·미납 청구서와 최근 사용량, 도구로 조회한 사용량·청구서입니다. 회선·월·항목으로 비교합니다. */
    private UserRecordClaims userRecords(AnswerMaterials materials) {
        return UserRecordClaims.of(materials.userDataSummary(), materials.toolRecords());
    }

    /** 비회원·없는 회원·조회 실패 안내는 가입 정보가 제공되지 않은 것으로 봅니다. */
    private boolean isUserDataFailure(String content) {
        return UserIntentHandler.NOT_FOUND_CONTENT.equals(content) || UserIntentHandler.LOGIN_REQUIRED_CONTENT.equals(content)
                || UserIntentHandler.LOOKUP_FAILED_CONTENT.equals(content);
    }

    private boolean hasUserData(AnswerMaterials materials) {
        // 기존 핸들러가 만든 조회 성공·실패 섹션을 구분합니다. 이름 표기나 필드 순서에는 의존하지 않습니다.
        return materials.sections().stream().filter(this::isUserDataSection)
                .anyMatch(section -> !isUserDataFailure(section.content()));
    }

    private boolean hasActualClaim(Pattern pattern, String sentence) {
        return hasActualClaim(pattern, sentence, null);
    }

    private boolean hasActualClaim(Pattern pattern, String sentence, String question) {
        Matcher claim = pattern.matcher(sentence);
        while (claim.find()) {
            // 가입 자체의 완료와, 가입 안내·설명을 마무리한 것은 구분합니다.
            if (pattern == TRANSACTION_CLAIM && GUIDANCE_COMPLETION.matcher(claim.group()).matches()
                    || pattern == TRANSACTION_RESULT_CLAIM
                            && GUIDANCE_COMPLETION.matcher(sentence.substring(0, claim.end())).find()) continue;
            // 사용자가 말한 기존 상황만 연결합니다. 봇의 완료 주장이나 뒤에 붙인 새 처리는 면제하지 않습니다.
            if (pattern == TRANSACTION_CLAIM && StringUtils.hasText(question)
                    && claim.group().matches(TRANSACTION_ACTION + "\\s*(?:을|를)?\\s*했는데(?:요)?")) {
                String context = sentence.substring(0, claim.end());
                String event = context.replaceFirst("^(?:고객|사용자|회원)(?:님)?(?:께서|이|가|은|는)\\s*", "")
                        .replaceFirst("했는데요$", "했는데");
                String provided = inspect(question).replaceFirst("^(?:제가|저는|내가|나는)\\s*", "")
                        .replaceFirst("했는데요", "했는데");
                String continuation = sentence.substring(claim.end());
                if (StringUtils.hasText(continuation) && !BOT_QUERY_SUBJECT.matcher(context).find()
                        && availabilityTopic(provided).replaceAll("\\s+", "")
                                .startsWith(availabilityTopic(event).replaceAll("\\s+", ""))
                        && !hasActualClaim(TRANSACTION_RESULT_CLAIM, continuation)) continue;
            }
            // 완료·조회 술어 바로 뒤의 조건·여부·인용만 확인합니다. 다른 절의 부정으로 앞의 실제 주장을 숨기지 않습니다.
            String local = sentence.substring(claim.end(), Math.min(sentence.length(), claim.end() + 24));
            local = local.replaceFirst("^((?:습니다|어요|어|다)?\\s*)['\"’”`」』]\\s*", "$1");
            if (!REPORTED_OR_HYPOTHETICAL.matcher(local).find()
                    && !DENIED_ASSERTION.matcher(local).find()
                    && !QUOTED_SECRET_REQUEST.matcher(local).find()) return true;
        }
        return false;
    }

    /** 다른 대상의 사실을 바꿔 적용한 설명과, 근거가 필요한 기능·기기 적용 주장을 문장별로 검사합니다. */
    private String removeMismatchedScopes(String answer, AnswerMaterials materials, List<String> sources,
            List<ScopedAnswerFact> evidence, List<String> payoffGoals, InspectionTrace trace) {

        List<String> kept = new ArrayList<>();
        boolean removed = false;
        int removedHeadings = 0;
        boolean previousRemoved = false;
        for (String paragraph : answer.split("\\R\\s*\\R")) {
            String headingGoal = null;
            List<String> safeParagraph = new ArrayList<>();
            int headingIndex = -1;
            int keptInstructions = 0;
            for (String sentence : availabilitySentences(paragraph)) {
                String inspected = inspect(sentence);
                Matcher heading = STOP_WORK_HEADING.matcher(inspected);
                if (heading.find()) {
                    if (headingIndex >= 0 && keptInstructions == 0) {
                        safeParagraph.remove(headingIndex);
                        removedHeadings++;
                    }
                    previousRemoved = false;
                    headingGoal = heading.group(1);
                    headingIndex = safeParagraph.size();
                    keptInstructions = 0;
                    safeParagraph.add(sentence);
                    continue;
                } else if (inspected.endsWith(":") || inspected.endsWith("：")) {
                    if (headingIndex >= 0 && keptInstructions == 0) {
                        safeParagraph.remove(headingIndex);
                        removedHeadings++;
                    }
                    previousRemoved = false;
                    headingGoal = null;
                    headingIndex = -1;
                }
                boolean mismatch = readScopeFacts(inspected, materials.question(), false).stream()
                        .anyMatch(claim -> hasScopeMismatch(claim, evidence));
                String payoffGoal = payoffGoal(inspected, headingGoal);
                mismatch |= payoffGoal != null && !payoffGoals.contains(payoffGoal);
                mismatch |= hasUnrequestedRoamingGuidance(inspected, materials.question());
                mismatch |= hasNumberPlusHidingClaim(inspected)
                        && sources.stream().noneMatch(this::hasNumberPlusHidingClaim);
                mismatch |= previousRemoved && hasUnsupportedDependentConclusion(inspected, materials, sources);
                if (mismatch) {
                    trace.removed(GuardRule.SCOPE_MISMATCH);
                    removed = true;
                    previousRemoved = true;
                } else {
                    previousRemoved = false;
                    safeParagraph.add(sentence);
                    if (headingIndex >= 0) keptInstructions++;
                }
            }
            if (headingIndex >= 0 && keptInstructions == 0) {
                safeParagraph.remove(headingIndex);
                removedHeadings++;
            }
            kept.addAll(safeParagraph);
        }
        if (removed) trace.removed(removedHeadings, GuardRule.EMPTY_HEADING);
        return removed ? String.join("\n", kept) : answer;
    }

    /** 삭제된 전제만 따르는 확정 결론·실행 지시는 제거하고, 직접 확인·질문·인용·독립 근거는 보존합니다. */
    private boolean hasUnsupportedDependentConclusion(String sentence, AnswerMaterials materials, List<String> sources) {
        Matcher dependent = DEPENDENT_SENTENCE.matcher(sentence);
        if (!dependent.find() || QUESTION_END.matcher(sentence).find()) return false;
        String conclusion = sentence.substring(dependent.end()).replaceFirst("^[,，\\s]+", "");
        if (hasSourceClaim(conclusion, sources)) return false;
        Matcher assertion = DEPENDENT_CONCLUSION_CLAIM.matcher(conclusion);
        List<ScopedAnswerFact> evidence = null;
        int previousEnd = 0;
        while (assertion.find()) {
            String prefix = conclusion.substring(previousEnd, assertion.start());
            String claimText = conclusion.substring(previousEnd, assertion.end());
            previousEnd = assertion.end();
            Matcher knowledge = KNOWLEDGE_RESTRICTION.matcher(prefix);
            if ((knowledge.find() && knowledge.group("knowledgeAction") != null)
                    || isUnassertedScope(conclusion.substring(assertion.end()))) continue;
            if (PROVIDED_MATERIAL.matcher(prefix).find()
                    && prefix.matches("(?s).*(?:설명|안내|답변)(?:해|하여)?\\s*드릴\\s*$")) continue;
            if (assertion.group().contains("없")
                    && prefix.matches("(?s).*(?:답변|응답)(?:에|에는|으로)\\s*(?:포함|추가)(?:할)?\\s*$")) continue;
            List<ScopedAnswerFact> claims = readScopeFacts(claimText, materials.question(), false);
            if (!claims.isEmpty()) {
                if (evidence == null) {
                    evidence = new ArrayList<>();
                    List<ScopedAnswerFact> collected = evidence;
                    materials.faqs().forEach(faq -> addScopeEvidence(faq.answer(), faq.question(), sources,
                            collected, new ArrayList<>()));
                    materials.sections().forEach(section -> addScopeEvidence(section.content(), materials.question(),
                            sources, collected, new ArrayList<>()));
                }
                List<ScopedAnswerFact> provided = evidence;
                if (claims.stream().allMatch(claim -> provided.stream().anyMatch(source -> source.kind() == claim.kind()
                        && compatibleScope(claim, source) && (claim.quantity() == null || source.quantity() != null
                        && source.quantity().accepts(claim.quantity().value()))))) continue;
            }
            return true;
        }
        return false;
    }

    private boolean hasUnrequestedRoamingGuidance(String sentence, String question) {
        if (!StringUtils.hasText(question) || QUESTION_END.matcher(sentence).find()) return false;
        String asked = inspect(question);
        if (!SIGNUP_DOCUMENT_QUERY.matcher(asked).find() || ROAMING_QUERY.matcher(asked).find()) return false;
        Matcher claim = ROAMING_USAGE_CLAIM.matcher(sentence);
        while (claim.find()) {
            // 가입 서류와 로밍을 함께 물었거나, 단순 인용·부정인 경우와 실제 이용 안내를 구분합니다.
            if (!isUnassertedScope(sentence.substring(claim.end()))) return true;
        }
        return false;
    }

    private boolean hasNumberPlusHidingClaim(String sentence) {
        if (QUESTION_END.matcher(sentence).find()) return false;
        Matcher claim = NUMBER_PLUS_HIDING_CLAIM.matcher(sentence);
        while (claim.find()) {
            // 두 번째 번호 발신·수신을 번호 숨김의 근거로 확대하지 않고, 인용·부정은 기존 검사로 구분합니다.
            if (!isUnassertedScope(sentence.substring(claim.end()))) return true;
        }
        return false;
    }

    private void addScopeEvidence(String text, String question, List<String> sources,
            List<ScopedAnswerFact> evidence, List<String> payoffGoals) {
        if (!StringUtils.hasText(text)) return;
        String headingGoal = stopQuestionGoal(question);
        for (String sentence : availabilitySentences(text)) {
            String inspected = inspect(sentence);
            if (!sources.stream().anyMatch(source -> source.contains(inspected))) continue;
            evidence.addAll(readScopeFacts(inspected, question, true));
            evidence.addAll(readSharingScopes(inspected, SHARING_USE_EVIDENCE));
            Matcher selection = PLAN_SELECTION.matcher(inspected);
            if (selection.find() && !QUESTION_END.matcher(inspected).find()
                    && !isUnassertedScope(inspected.substring(selection.end()))
                    && (inspected.contains("요금제") || StringUtils.hasText(question) && question.contains("요금제"))) {
                String device = deviceScope(inspected, question);
                if (!device.equals("*")) evidence.add(new ScopedAnswerFact(AnswerScopeKind.DEVICE_PLAN, device + "|*", null));
            }
            Matcher heading = STOP_WORK_HEADING.matcher(inspected);
            if (heading.find()) headingGoal = heading.group(1);
            String goal = payoffGoal(inspected, headingGoal);
            if (goal != null) payoffGoals.add(goal);
            else {
                Matcher guidance = PAYOFF_GUIDANCE.matcher(inspected);
                if (guidance.find() && !QUESTION_END.matcher(inspected).find()
                        && !STOP_PAYOFF_CORRECTION.matcher(inspected).find()
                        && !isUnassertedScope(inspected.substring(guidance.end()))) payoffGoals.add("완납");
            }
        }
    }

    private List<ScopedAnswerFact> readScopeFacts(String sentence, String question, boolean source) {
        List<ScopedAnswerFact> facts = new ArrayList<>();
        if (QUESTION_END.matcher(sentence).find()) return facts;
        // 계산 예외는 아래 금액 검사에만 적용하고, 기능·기기·연락처 주장은 계속 확인합니다.
        facts.addAll(readSharingScopes(sentence, SHARING_APPLICATION_FACT));
        Matcher plan = PLAN_SCOPE_FACT.matcher(sentence);
        while (plan.find()) {
            if (isUnassertedScope(sentence.substring(plan.end()))) continue;
            String device = deviceScope(sentence.substring(0, plan.end()), question);
            String planType = plan.group(1) == null ? "*" : plan.group(1).toUpperCase(Locale.ROOT);
            facts.add(new ScopedAnswerFact(AnswerScopeKind.DEVICE_PLAN, device + "|" + planType, null));
        }
        Matcher feature = FEATURE_SCOPE_FACT.matcher(sentence);
        while (feature.find()) {
            if (isUnassertedScope(sentence.substring(feature.end()))) continue;
            String name = feature.group(1).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
            AnswerScopeKind kind = name.equals("esim") ? AnswerScopeKind.ESIM_COMPATIBILITY
                    : name.equals("유심") ? AnswerScopeKind.SIM_COMPATIBILITY : AnswerScopeKind.SHARING_COMPATIBILITY;
            facts.add(new ScopedAnswerFact(kind, deviceScope(sentence.substring(0, feature.end()), question), null));
        }
        Matcher retention = RETENTION_SCOPE_FACT.matcher(sentence);
        while (retention.find()) {
            if (isUnassertedScope(sentence.substring(retention.end()))) continue;
            String name = retention.group(1).replaceAll("\\s+", "");
            String prefix = sentence.substring(0, retention.start());
            boolean contact = name.equals("연락처") || name.equals("주소록")
                    || prefix.matches(".*(?:연락처|주소록)(?:의|에\\s*저장된)\\s*$");
            String target = contact ? "연락처" : "전화번호";
            // 보존·복구 가능 안내와 복구 제한 안내를 서로의 근거로 쓰지 않습니다.
            if (retention.group(2) != null) target += "|복구제한";
            facts.add(new ScopedAnswerFact(AnswerScopeKind.RETENTION, target, null));
        }
        facts.addAll(readPlanPrices(sentence, question, source));
        Matcher fee = FEE_SCOPE_FACT.matcher(sentence);
        while (fee.find()) {
            if (isDerivedQuantity(sentence, fee.start(), fee.end())) continue;
            if (isUnassertedScope(sentence.substring(fee.end()))) continue;
            String context = sentence.substring(0, fee.end());
            String target = serviceScope(context, question);
            String amount = "요금 " + sentence.substring(Math.max(0, fee.start() - 6), fee.end());
            readFacts(amount).stream().filter(fact -> fact.metric().equals("요금") && fact.unit().equals("원"))
                    .findFirst().ifPresent(fact -> facts.add(new ScopedAnswerFact(AnswerScopeKind.SERVICE_FEE, target, fact)));
        }
        return facts;
    }

    private List<ScopedAnswerFact> readSharingScopes(String sentence, Pattern relation) {
        List<ScopedAnswerFact> facts = new ArrayList<>();
        if (QUESTION_END.matcher(sentence).find()) return facts;
        Matcher application = relation.matcher(sentence);
        while (application.find()) {
            if (isUnassertedScope(sentence.substring(application.end()))) continue;
            Matcher devices = DEVICE_SCOPE.matcher(application.group());
            while (devices.find()) {
                facts.add(new ScopedAnswerFact(AnswerScopeKind.SHARING_APPLICATION, deviceName(devices), null));
            }
            if (application.group().contains("테더링")) {
                facts.add(new ScopedAnswerFact(AnswerScopeKind.SHARING_APPLICATION, "테더링", null));
            }
        }
        return facts;
    }

    private boolean hasScopeMismatch(ScopedAnswerFact claim, List<ScopedAnswerFact> evidence) {
        if (claim.target().startsWith("*")) return false;
        List<ScopedAnswerFact> related = evidence.stream().filter(source -> source.kind() == claim.kind()).toList();
        if (related.isEmpty()) return claim.kind() == AnswerScopeKind.SHARING_APPLICATION;
        if (claim.kind() == AnswerScopeKind.PLAN_PRICE) {
            // 대상·금액 종류·단위가 명확히 같은 근거와만 비교합니다. 다른 대상의 값으로 대신 통과시키지 않습니다.
            List<ScopedAnswerFact> samePlan = related.stream().filter(source -> source.target().equals(claim.target())
                    && (source.quantity().metric().equals(claim.quantity().metric())
                    || claim.quantity().metric().equals("일반요금") && (source.quantity().metric().equals("요금")
                    || source.quantity().metric().equals("할인후요금")))
                    && source.quantity().unit().equals(claim.quantity().unit())).toList();
            return !samePlan.isEmpty() && samePlan.stream().noneMatch(source -> source.quantity().accepts(claim.quantity().value()));
        }
        if (claim.kind() == AnswerScopeKind.SERVICE_FEE) {
            // 값이 다른 서비스의 금액과만 일치할 때 그 서비스를 바꿔 붙인 것으로 봅니다. 기존 수치 검사도 유지합니다.
            if (related.stream().anyMatch(source -> source.target().equals("*"))) return false;
            related = related.stream().filter(source -> source.quantity().accepts(claim.quantity().value())).toList();
            if (related.isEmpty()) return false;
        }
        return related.stream().noneMatch(source -> compatibleScope(claim, source));
    }

    private boolean compatibleScope(ScopedAnswerFact claim, ScopedAnswerFact source) {
        if (claim.kind() == AnswerScopeKind.RETENTION || claim.kind() == AnswerScopeKind.SERVICE_FEE) {
            return source.target().equals("*") || claim.target().equals(source.target());
        }
        if (claim.kind() == AnswerScopeKind.SHARING_APPLICATION
                && source.target().contains(":") && !claim.target().contains(":")) return false;
        if (claim.kind() == AnswerScopeKind.DEVICE_PLAN) {
            String[] expected = claim.target().split("\\|", -1);
            String[] provided = source.target().split("\\|", -1);
            return compatibleDevice(expected[0], provided[0])
                    && (provided[1].equals("*") || expected[1].equals("*") || expected[1].equals(provided[1]));
        }
        return compatibleDevice(claim.target(), source.target());
    }

    private boolean compatibleDevice(String claim, String source) {
        return source.equals("*") || claim.equals(source)
                || source.equals("PHONE") && claim.startsWith("PHONE:")
                || source.equals("TABLET") && claim.startsWith("TABLET:")
                || claim.equals("PHONE") && source.startsWith("PHONE:")
                || claim.equals("TABLET") && source.startsWith("TABLET:");
    }

    private String deviceScope(String text, String question) {
        Matcher devices = DEVICE_SCOPE.matcher(text);
        String target = "*";
        while (devices.find()) target = deviceName(devices);
        if (target.equals("*") || target.equals("PHONE") || target.equals("TABLET")) {
            Matcher asked = DEVICE_SCOPE.matcher(StringUtils.hasText(question) ? question : "");
            List<String> requested = new ArrayList<>();
            while (asked.find()) requested.add(deviceName(asked));
            String fromQuestion = requested.stream().distinct().count() == 1 ? requested.getFirst() : "*";
            if (target.equals("*") || fromQuestion.startsWith(target + ":")) target = fromQuestion;
        }
        return target;
    }

    private String deviceName(Matcher device) {
        String name = (device.group(2) == null ? device.group(3) : device.group(2)).replaceAll("\\s+", "");
        String type = name.equals("태블릿") ? "TABLET" : name.equals("노트북") ? "NOTEBOOK"
                : name.contains("워치") ? "WATCH" : "PHONE";
        return device.group(1) == null ? type : type + ":" + device.group(1).toUpperCase(Locale.ROOT);
    }

    private String serviceScope(String text, String question) {
        Matcher service = SERVICE_SCOPE.matcher(text);
        String target = "*";
        while (service.find()) target = serviceName(service.group());
        if (!target.equals("*")) return target;
        Matcher asked = SERVICE_SCOPE.matcher(StringUtils.hasText(question) ? question : "");
        List<String> scopes = new ArrayList<>();
        while (asked.find()) scopes.add(serviceName(asked.group()));
        return scopes.stream().distinct().count() == 1 ? scopes.getFirst() : "*";
    }

    /** 제공 문장의 명시적인 요금제·금액 종류만 연결합니다. 계산 결과나 생략된 복수 대상은 추정하지 않습니다. */
    private List<ScopedAnswerFact> readPlanPrices(String sentence, String question, boolean source) {
        if (QUESTION_END.matcher(sentence).find()) return List.of();
        String context = sentence + " " + (question == null ? "" : question);
        if (!context.contains("요금제") && !context.matches("(?is).*(?:5G|LTE).*")) return List.of();
        List<ScopedAnswerFact> facts = new ArrayList<>();
        Matcher price = PLAN_PRICE_FACT.matcher(sentence);
        String current = null;
        int previousEnd = 0;
        while (price.find()) {
            String before = sentence.substring(previousEnd, price.start());
            // 직전 금액의 정정·연결 표현만 건너뜁니다. 새 요금제나 별도 문장의 대상은 추정하지 않습니다.
            String connector = before;
            Matcher correction = UNCONFIRMED_QUANTITY.matcher(connector);
            if (correction.lookingAt()) connector = connector.substring(correction.end());
            connector = connector.replaceFirst("^\\s*\\((?:부가(?:가치)?세|(?i:VAT))\\s*포함\\)\\s*", "");
            if (previousEnd > 0) connector = connector.replaceFirst("^\\s*(?:으로|로)\\s+", "");
            boolean continuation = connector.matches("^[\\s,，;；]*(?:(?:입니다|예요|이며|이고|으로|로"
                    + "|(?:이|가|은|는)?\\s*(?:아니라|아니고))\\s*[,，;；]?\\s*)?"
                    + "(?:(?:정확한|실제|원래|올바른)\\s+)?(?:에\\s*)?"
                    + "(?:(?:[0-9.,]+\\s*%\\s*)?할인(?:이|을)?\\s*(?:적용되어|적용된|적용하면|받으면|적용한\\s*후)\\s*)?$");
            String explicit = continuation ? null
                    : planPriceSubject(readQuantityClause(connector, connector.length(), connector.length()));
            if (explicit != null) current = explicit;
            else if (!continuation) current = null;
            if (current == null && StringUtils.hasText(question)
                    && (continuation || before.matches("^\\s*(?:이|그|해당)?\\s*요금제(?:의|은|는)?\\s*$"))
                    && question.indexOf("요금제") >= 0 && question.indexOf("요금제") == question.lastIndexOf("요금제")) {
                String named = question.substring(0, question.indexOf("요금제"));
                // 질문에 둘 이상의 대상이 적혀 있으면 마지막 이름을 임의로 골라 쓰지 않습니다.
                if (!named.matches(".*(?:과|와|및|또는|각각|[/·]).*")) current = planPriceSubject(named);
            }
            previousEnd = price.end();
            String tail = sentence.substring(price.end());
            if (current == null || isDerivedQuantity(sentence, price.start(), price.end())
                    || isUnassertedScope(tail) || QUALIFIED_NUMBER.matcher(tail).lookingAt()
                    || UNCONFIRMED_QUANTITY.matcher(tail).find()) continue;
            String after = sentence.substring(price.end(), Math.min(sentence.length(), price.end() + 8));
            String metric = planPriceMetric(price.group("label"), before, after);
            if (!source && metric.equals("요금") && price.group("label").replaceAll("\\s+", "").matches("월|요금|월요금")) {
                metric = "일반요금";
            }
            String amount = "요금 " + sentence.substring(price.end("label"), price.end()) + after;
            String target = current;
            String priceMetric = metric;
            readFacts(amount).stream().filter(fact -> fact.unit().equals("원")).findFirst()
                    .ifPresent(fact -> facts.add(new ScopedAnswerFact(AnswerScopeKind.PLAN_PRICE, target,
                            new QuantityFact(priceMetric, fact.value(), fact.unit(), fact.bound()))));
        }
        return facts;
    }

    private String planPriceSubject(String text) {
        String name = text.strip().replaceFirst("^[,，;；\\s]+", "")
                .replaceFirst("^(?:입니다|예요|이며|이고|이지만)\\s*[,，;；]?\\s*", "")
                .replaceFirst("^(?:반면|다만|또한|그리고|실제|정확한)\\s+", "")
                .replaceFirst("^(?:(?:계산|합산|차감)(?:\\s*방법)?(?:과|와)\\s*(?:관계없이|별개로)"
                        + "|(?:계산|합산|차감)(?:을|를)\\s*하지\\s*않은|(?:계산|합산|차감)\\s*전"
                        + "|가정(?:이|은)\\s*아니라|예를\\s*들\\s*필요\\s*없이)\\s*", "")
                .replaceFirst("요금제(?:의|은|는|이|가)?\\s*$", "")
                .replaceFirst("(?:의|은|는)\\s*$", "").strip();
        if (name.isEmpty() || name.split("\\s+").length > 4
                || !name.matches("[\\p{L}\\p{N}][\\p{L}\\p{N} +._-]{0,64}")
                || name.matches(".*(?:경우|기준|할인|적용|부가서비스|건당|초과|위약금|수수료|이며|이고|입니다|각각|또는|\\s및\\s).*")) return null;
        if (List.of("이", "그", "해당", "현재", "기존", "전체", "기본", "원래", "요금", "월정액", "요금제").contains(name)) return null;
        return name.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private String planPriceMetric(String label, String before, String after) {
        String normalized = label.replaceAll("\\s+", "");
        if (after.matches("^\\s*에서.*")) return "요금";
        if (normalized.contains("후") || normalized.contains("최종")) return "할인후요금";
        if (normalized.startsWith("할인")) return "할인";
        if (before.matches("(?s).*(?:[0-9.,]+\\s*%\\s*)?할인(?:이|을)?\\s*(?:적용되어|적용된|적용하면|받으면|적용한\\s*후)\\s*$")) return "할인후요금";
        return "요금";
    }

    private String serviceName(String name) {
        String normalized = name.replaceAll("\\s+", "");
        if (normalized.equals("컬러링")) return "통화연결음";
        return normalized.replace("스마트워치회선", "워치회선");
    }

    private boolean isUnassertedScope(String tail) {
        // 닫는 따옴표 뒤의 '라는 안내'도 기존 인용·가정 검사를 재사용해 사실과 구분합니다.
        return isUnassertedRequirement(tail.replaceFirst("^[\\s'\"’”`]+", ""));
    }

    private String payoffGoal(String sentence, String headingGoal) {
        if (QUESTION_END.matcher(sentence).find() || STOP_PAYOFF_CORRECTION.matcher(sentence).find()) return null;
        Matcher relation = STOP_PAYOFF_LINK.matcher(sentence);
        if (relation.find() && !isUnassertedScope(sentence.substring(relation.end()))) {
            String goal = relation.group(1) == null ? relation.group(2) : relation.group(1);
            return goal.contains("해지") ? "해지" : "정지";
        }
        Matcher guidance = PAYOFF_GUIDANCE.matcher(sentence);
        return headingGoal != null && guidance.find() && !isUnassertedScope(sentence.substring(guidance.end()))
                ? headingGoal : null;
    }

    private String stopQuestionGoal(String question) {
        if (!StringUtils.hasText(question) || question.contains("할부") || question.contains("완납")) return null;
        if (question.contains("정지")) return "정지";
        return question.contains("해지") ? "해지" : null;
    }

    /** FAQ에 없는 업무 불가·미지원·강제 분리 조건만 제거합니다. 정보 부족이나 안전한 거절은 정책 제한이 아닙니다. */
    private String removeUnsupportedRestrictions(String answer, AnswerMaterials materials, List<String> sources,
            InspectionTrace trace) {
        List<AvailabilityClaim> evidence = new ArrayList<>();
        materials.faqs().forEach(faq -> addRestrictionEvidence(faq.answer(), faq.question(), sources, evidence));
        materials.sections().forEach(section ->
                addRestrictionEvidence(section.content(), materials.question(), sources, evidence));
        List<String> kept = new ArrayList<>();
        boolean removed = false;
        boolean previousRemoved = false;
        for (String sentence : availabilitySentences(answer)) {
            boolean missingDataNotice = !hasUserData(materials) && materials.sections().stream().anyMatch(section ->
                    isUserDataSection(section) && isUserDataFailure(section.content()))
                    && sentence.matches("(?s).*(?:정보|자료|데이터)(?:가|이|는|은)?\\s*없(?:어|기\\s*때문|으므로).*제공할\\s*수\\s*없.*");
            boolean unsupported = readRestrictionClaims(sentence, materials.question()).stream()
                    .anyMatch(claim -> !(missingDataNotice && isPersonalDataRestriction(claim))
                            && !hasRestrictionEvidence(claim, materials.question(), evidence));
            // 답변의 계산 도입만 생략해 다시 비교합니다. 원문 근거의 요일·대상·제한 조건은 지우지 않습니다.
            String factualClaim = DERIVED_RESTRICTION_LEAD.matcher(inspect(sentence)).replaceFirst("");
            if (unsupported && !factualClaim.equals(inspect(sentence))) {
                unsupported = readRestrictionClaims(factualClaim, materials.question()).stream()
                        .anyMatch(claim -> !hasRestrictionEvidence(claim, materials.question(), evidence));
            }
            unsupported |= previousRemoved && hasUnsupportedDependentConclusion(inspect(sentence), materials, sources);
            if (unsupported) {
                trace.removed(GuardRule.UNSUPPORTED_RESTRICTION);
                removed = true;
                previousRemoved = true;
            } else {
                previousRemoved = false;
                kept.add(sentence);
            }
        }
        return removed ? String.join("\n", kept) : answer;
    }


    /** 질문에 적힌 하나의 제한 주어만 연결합니다. 서로 다른 제한을 임의로 같은 대상으로 보지 않습니다. */
    private String resolveRestrictionReference(String key, String question) {
        if (!key.matches("^(?:이|그|해당)제한.*") || !StringUtils.hasText(question)) return key;
        String topic = conditionTopicKey(question);
        int first = topic.indexOf("제한");
        if (first < 1 || first != topic.lastIndexOf("제한")) return key;
        String subject = topic.substring(0, first + 2);
        return key.replaceFirst("^(?:이|그|해당)제한", Matcher.quoteReplacement(subject));
    }


    /** 정보 부재 안내의 예외는 제공 대상이 개인 자료일 때만 적용합니다. 서비스 미제공은 계속 검사합니다. */
    private boolean isPersonalDataRestriction(AvailabilityClaim claim) {
        int actionIndex = claim.key().lastIndexOf('|');
        return claim.key().endsWith("|제공") && actionIndex > 0
                && PERSONAL_DATA_RESTRICTION_OBJECT.matcher(claim.key().substring(0, actionIndex)).find();
    }

    private boolean hasRestrictionEvidence(AvailabilityClaim claim, String question,
            List<AvailabilityClaim> evidence) {
        String resolved = resolveRestrictionReference(claim.key(), question);
        if (evidence.stream().anyMatch(source -> source.key().equals(resolved) || source.key().equals(claim.key())
                || sameConditionTopic(claim.key(), source.key(), question))) return true;
        int actionIndex = claim.key().lastIndexOf('|');
        String topic = claim.key().substring(0, actionIndex);
        String action = claim.key().substring(actionIndex + 1);
        if (!StringUtils.hasText(question) || RESTRICTION_SERVICE.matcher(topic).find()
                || DEVICE_SCOPE.matcher(topic).find()) return false;
        List<String> services = RESTRICTION_SERVICE.matcher(question).results()
                .map(match -> serviceName(match.group())).distinct().toList();
        if (services.size() != 1) return false;
        // 서비스 이름만 보충하며 기존 대상·요일·조건과 행위는 그대로 비교합니다.
        String key = restrictionTopic(services.getFirst() + " " + topic) + "|" + action;
        return evidence.stream().anyMatch(source -> source.key().equals(key));
    }

    private void addRestrictionEvidence(String text, String question, List<String> sources,
            List<AvailabilityClaim> evidence) {
        if (!StringUtils.hasText(text)) return;
        String previous = null;
        for (String sentence : providedSourceSentences(text, sources)) {
            evidence.addAll(readRestrictionClaims(sentence.replace("없으므로", "없으며")
                    .replace("않으므로", "않으며"), question, true));
            // 일반 쉐어링 제한은 그대로 두고, FAQ의 기기가 하나일 때만 명시적 기기 안내의 근거도 보충합니다.
            if (sentence.contains("쉐어링")) {
                String targeted = restrictionDeviceContext(sentence, question);
                if (!targeted.equals(sentence)) evidence.addAll(readRestrictionClaims(targeted, question, true));
            }
            List<String> services = RESTRICTION_SERVICE.matcher(StringUtils.hasText(question) ? question : "").results()
                    .map(match -> serviceName(match.group())).distinct().toList();
            if (services.size() == 1 && !RESTRICTION_SERVICE.matcher(sentence).find()
                    && !DEVICE_SCOPE.matcher(sentence).find()) {
                evidence.addAll(readRestrictionClaims(services.getFirst() + " " + sentence, question, true));
            }
            // 같은 FAQ에서 바로 앞의 설정 설명을 생략한 '해제하면' 등에만 주어를 이어 붙입니다.
            Matcher setting = RESTRICTION_SETTING_CONTEXT.matcher(previous == null ? "" : inspect(previous));
            if (setting.matches() && inspect(sentence).startsWith(setting.group(2) + "하면")) {
                evidence.addAll(readRestrictionClaims(setting.group(1) + sentence, question, true));
            }
            // '전까지만 가능'은 그 시점 이후 불가의 명시적인 근거입니다. 일반 권고에서는 추론하지 않습니다.
            Matcher limit = ONLY_BEFORE_RESTRICTION.matcher(inspect(sentence));
            if (limit.matches()) evidence.addAll(readRestrictionClaims(limit.group(1) + limit.group(2)
                    + " 이후에는 " + limit.group(3) + "할 수 없습니다.", question, true));
            previous = sentence;
        }
    }

    private List<AvailabilityClaim> readRestrictionClaims(String text, String question) {
        return readRestrictionClaims(text, question, false);
    }

    private List<AvailabilityClaim> readRestrictionClaims(String text, String question, boolean source) {
        String sentence = inspect(text).replaceAll("쓸\\s*수\\s*없", "사용할 수 없");
        List<AvailabilityClaim> claims = new ArrayList<>();
        if (QUESTION_END.matcher(sentence).find()) return claims;
        // 본문은 바꾸지 않고, 제공 항목의 '없다'만 동일한 미제공 주장으로 읽습니다.
        sentence = RESTRICTION_PROVISION_ABSENCE.matcher(sentence).replaceAll(absence ->
                Matcher.quoteReplacement(absence.group("items") + "는 제공하지 않습니다"));
        Matcher restriction = RESTRICTION_CLAIM.matcher(sentence);
        int previousEnd = 0;
        while (restriction.find()) {
            String context = sentence.substring(previousEnd, restriction.start());
            previousEnd = restriction.end();
            if (isUnassertedScope(sentence.substring(restriction.end()))
                    || KNOWLEDGE_RESTRICTION.matcher(context).find()) continue;
            String action = restrictionAction(restriction.group(1) == null ? restriction.group(3) : restriction.group(1));
            // 새 인과·연결 표현은 명확한 제공 항목만 읽습니다. 자료 부족이나 다른 업무는 기존 기준을 유지합니다.
            if (("미제공".equals(restriction.group(3))
                    || restriction.group(2) != null && restriction.group(2).matches("(?:하지|되지)\\s*않(?:아|고)"))
                    && (!action.equals("제공") || !RESTRICTION_PROVISION_CONTEXT.matcher(context).find())) continue;
            // 자료의 답변을 바꾸지 않겠다는 안내는 서비스 변경 불가 주장이 아닙니다.
            if (action.equals("변경") && context.matches("(?s).*답변(?:을|를)?\\s*$")) continue;
            // 새 연결형 불가 표현은 취소·변경에만 적용하고, 다른 업무의 기존 검사 범위는 유지합니다.
            if (!action.matches("취소|변경") && restriction.group(2) != null
                    && restriction.group(2).replaceAll("\\s+", "").matches("불가(?:능)?(?:하며|하고|하지만)")) continue;
            // '번호이동 불가'에서도 번호이동 자체가 명시된 업무 대상입니다.
            if (action.equals("번호이동")) {
                context += " 번호이동";
                action = "신청";
            }
            if (action.equals("제공") && addProvisionRestrictions(context, question, source, claims)) continue;
            if (action.equals("사용")) context = conditionalUsageContext(context, question);
            String topic = restrictionTopic(context);
            if (topic.isEmpty() && StringUtils.hasText(question)) {
                Matcher asked = RESTRICTION_QUESTION.matcher(question);
                while (asked.find()) {
                    if (restrictionAction(asked.group(1)).equals(action)) {
                        topic = restrictionTopic(question.substring(0, asked.start()));
                        break;
                    }
                }
            }
            if (!topic.isEmpty()) claims.add(new AvailabilityClaim(topic + "|" + action, AvailabilityState.DENIED, -1));
        }
        Matcher separate = SEPARATE_PROCEDURE.matcher(sentence);
        if (separate.find() && !isUnassertedScope(sentence.substring(separate.end()))) {
            String topic = restrictionTopic(sentence.substring(0, separate.start()));
            if (!topic.isEmpty()) claims.add(new AvailabilityClaim(topic + "|분리처리", AvailabilityState.DENIED, -1));
        }
        // 자료에 없다는 설명과 서비스·정책 자체가 없다는 주장은 다릅니다.
        Matcher absence = SERVICE_ABSENCE_CLAIM.matcher(sentence);
        while (absence.find()) {
            if (isUnassertedScope(sentence.substring(absence.end()))) continue;
            String context = sentence.substring(0, absence.start());
            String topic = restrictionTopic(context + absence.group("subject"));
            if (!topic.isEmpty()) claims.add(new AvailabilityClaim(topic + "|존재", AvailabilityState.DENIED, -1));
        }
        return claims;
    }

    /** 상태 변화 조건과 사용 제한을 함께 비교합니다. 요청만으로 처리 완료를 추정하지 않습니다. */
    private String conditionalUsageContext(String context, String question) {
        Matcher condition = USAGE_CHANGE_CONDITION.matcher(context);
        if (!condition.find()) return context;
        String actor = context.substring(0, condition.start()).strip();
        // 행위 주체만 생략합니다. 앞에 추가 조건·부정·다른 업무가 있으면 기존 비교를 유지합니다.
        if (!actor.isEmpty() && !actor.matches("(?:통신사|고객센터)(?:에서|에)")) return context;
        String effect = context.substring(condition.end()).strip();
        Matcher result = USAGE_CHANGE_RESULT.matcher(effect);
        boolean statedResult = result.lookingAt();
        if (condition.group("request") != null && (!statedResult
                || !condition.group("action").equals(result.group("action")))) return context;
        if (statedResult) {
            if (!condition.group("action").equals(result.group("action"))) return context;
            effect = result.group("target") + "으로 " + effect.substring(result.end());
        }
        // 조건은 키에 남기고 기기 주어만 기존 질문의 명시적 대상에서 보충합니다.
        return restrictionDeviceContext(condition.group("condition") + "하면 " + effect, question);
    }

    private boolean addProvisionRestrictions(String context, String question, boolean source, List<AvailabilityClaim> claims) {
        Matcher provided = RESTRICTION_PROVISION_CONTEXT.matcher(context);
        if (!provided.find()) return false;
        String scope = context.substring(0, provided.start())
                .replaceAll("데이터\\s*전용\\s*(?:상품|요금제)(?:으로|로|이며|이고|인)\\s*", "");
        // 수치를 버리지 않고 기존 숫자·단위 읽기로 표준화합니다. 사람·기간·조건은 그대로 둡니다.
        String quantified = RESTRICTION_PROVISION_LEAD.matcher(scope).replaceAll(lead -> Matcher.quoteReplacement(
                readFacts("월 요금 " + lead.group("money") + " 데이터 " + lead.group("data")).stream()
                        .map(fact -> fact.metric() + fact.value().stripTrailingZeros().toPlainString() + fact.unit())
                        .reduce("", String::concat) + " "));
        List<String> scopes = new ArrayList<>();
        scopes.add(restrictionDeviceContext(quantified, question));
        // 제공 자료의 미제공 사실은 가격을 생략한 안내의 근거로도 쓸 수 있습니다. 답변의 수치는 생략하지 않습니다.
        if (source && !quantified.equals(scope)) scopes.add(restrictionDeviceContext(
                RESTRICTION_PROVISION_LEAD.matcher(scope).replaceAll(""), question));
        Matcher items = RESTRICTION_PROVISION_ITEMS.matcher(provided.group("items"));
        while (items.find()) {
            String item = items.group().matches(".*(?:음성|통화).*") ? "통화" : "문자";
            for (String scoped : scopes) claims.add(new AvailabilityClaim(
                    restrictionTopic(scoped + " " + item) + "|제공", AvailabilityState.DENIED, -1));
        }
        return true;
    }

    private String restrictionDeviceContext(String context, String question) {
        if (DEVICE_SCOPE.matcher(context).find() || !StringUtils.hasText(question)
                || deviceScope("", question).equals("*")) return context;
        Matcher device = DEVICE_SCOPE.matcher(question);
        return device.find() ? device.group() + " " + context : context;
    }

    private String restrictionTopic(String context) {
        Matcher after = AFTER_LIMIT_CONTEXT.matcher(context.strip());
        if (after.matches()) return conditionTopicKey(after.group(2)) + "|기한이후:" + conditionTopicKey(after.group(1));
        Matcher targetAfter = TARGET_AFTER_LIMIT_CONTEXT.matcher(context.strip());
        if (targetAfter.matches()) return conditionTopicKey(targetAfter.group(1)) + "|기한이후:" + conditionTopicKey(targetAfter.group(2));
        context = RESTRICTION_DEVICE_OWNER.matcher(context).replaceAll(device ->
                Matcher.quoteReplacement(device.group().substring(0, device.group().length() - 1)));
        if (context.contains("쉐어링")) {
            context = context.replaceAll("(?:쉐어링\\s*데이터|합산\\s*(?:데이터\\s*)?제공량)(?:을|를|이|가)?\\s*"
                    + "(?:다\\s*쓰면|모두\\s*사용하면|소진(?:하|되)면)", "공유제공량소진하면")
                    .replaceAll("(?:데이터\\s*)?쉐어링", "데이터쉐어링");
        }
        // 이름의 마지막 '이'를 조사로 떼지 않도록 같은 무선 서비스 표기를 먼저 통일합니다.
        String normalized = conditionContext(localConditionContext(context)).replaceAll("와이파이|(?i:wi[- ]?fi)", "WiFi").replaceAll("(해지|정지|해제)\\s*(?:한\\s*경우(?:에는|에도)?|시(?:에는|에도|(?=\\s|$)))", "$1하면")
                .replaceAll("연결(?:된)?\\s*동안(?:에는|에도)?|연결\\s*중(?:에는|에도)?", "연결중");
        String topic = availabilityTopic(normalized)
                .replaceAll("(?<![가-힣a-zA-Z0-9-])(망|앱|폰)(?:은|는|을|를|이|가|에|로)(?=\\s|$)", "$1")
                .replaceAll("\\s+", "").toLowerCase(Locale.ROOT)
                .replace("컬러링", "통화연결음").replace("스마트워치회선", "워치회선").replace("더이상", "");
        List<String> devices = DEVICE_SCOPE.matcher(topic).results()
                .map(match -> deviceScope(match.group(), "")).distinct().toList();
        List<String> services = RESTRICTION_SERVICE.matcher(topic).results()
                .map(match -> serviceName(match.group()).toLowerCase(Locale.ROOT)).distinct().toList();
        // 같은 대상의 반복은 묶고, 실제로 다른 대상이 여러 개면 단일 대상으로 합치지 않습니다.
        if (services.size() == 1 && devices.size() <= 1) {
            String target = devices.isEmpty() ? "*" : devices.getFirst();
            String rest = DEVICE_SCOPE.matcher(topic).replaceAll("");
            rest = RESTRICTION_SERVICE.matcher(rest).replaceAll("");
            return target + "|" + services.getFirst() + "|" + rest;
        }
        return topic;
    }

    /** 비교용 복사본에만 일반 문법을 정리합니다. 조건의 대상·수치·부정은 유지합니다. */
    private String conditionContext(String text) {
        return text.replaceAll("필요하기\\s*때문에", "필요하므로")
                .replaceAll("있는\\s*경우|있다면", "있으면")
                .replaceAll("없는\\s*경우|없다면", "없으면")
                .replaceAll("법정대리인의", "법정대리인")
                .replaceAll("본인\\s*확인", "본인인증")
                .replaceAll("이므로|이라(?=\\s)", "이므로")
                .replaceAll("(?:회선\\s*)?일시\\s*정지\\s*(?:기간\\s*동안|기간|중)(?:에는|에|동안)?", "일시정지중")
                .replaceAll("찾는\\s*기능", "찾기 기능")
                .replaceAll("(?<![가-힣])또는(?=\\s)", " ")
                .replaceAll("([가-힣a-zA-Z0-9]+?)(?:에서는|에서도|에는|에도|으로는|로는|에서|으로|은|는|을|를|의|에|로)(?=\\s|$)", "$1 ")
                .replaceAll("이미\\s*가입된", "이미 가입한")
                .replaceAll("(가입|개통|신청)(?:을|를)?\\s*(?:할\\s*때|하려면)", "$1시")
                .replaceAll("(?:회선\\s*|이용\\s*)?정지\\s*중(?:에도|에는|에|도)?", "정지중");
    }

    /** 독립된 앞 설명과 한정 조건을 섞지 않습니다. 금액 쉼표·괄호·조건의 나열은 유지합니다. */
    private String localConditionContext(String text) {
        int start = 0;
        Matcher attribution = QUERY_SOURCE_ATTRIBUTION.matcher(text);
        while (attribution.find()) start = attribution.end();
        int depth = 0;
        for (int index = start; index < text.length(); index++) {
            char c = text.charAt(index);
            if ("([（［".indexOf(c) >= 0) depth++;
            if (")]）］".indexOf(c) >= 0) depth--;
            if (depth != 0 || ",，;；".indexOf(c) < 0 && !Character.isWhitespace(c)) continue;
            if (index > 0 && index + 1 < text.length()
                    && Character.isDigit(text.charAt(index - 1)) && Character.isDigit(text.charAt(index + 1))) continue;
            String prefix = text.substring(start, index).strip();
            if (ASSERTED_CLAUSE_END.matcher(prefix).find() || QUERY_FACT_CONNECTIVE_END.matcher(prefix).find()
                    || prefix.matches("(?s).*(?:없으며|있으며|아니며|되고|되며|있지만|없지만|있고|없고)$")) start = index + 1;
        }
        String local = text.substring(start).strip();
        if (local.isEmpty()) return text;
        if (start == 0) return local;
        Matcher topic = REQUIREMENT_SUBJECT.matcher(text.substring(0, start));
        if (!topic.find()) return local;
        String subject = topic.group(1);
        if (subject.length() > 30 || !RESTRICTION_SERVICE.matcher(subject).find()
                && !DEVICE_SCOPE.matcher(subject).find() && !subject.contains("요금제")
                || subject.matches("(?s).*(?:할|하면|필요|없|있|기억).*")) return local;
        return conditionTopicKey(local).contains(conditionTopicKey(subject)) ? local : subject + " " + local;
    }

    /** 주어만 생략된 경우에는 질문에 명시된 동일 주어만 보충합니다. 조건·수치·행위는 지우지 않습니다. */
    private boolean sameConditionTopic(String claim, String source, String question) {
        if (!StringUtils.hasText(question)) return false;
        String normalizedClaim = conditionTopicKey(claim);
        String normalizedSource = conditionTopicKey(source);
        if (normalizedClaim.equals(normalizedSource)) return true;
        String normalizedQuestion = conditionTopicKey(question);
        if (normalizedQuestion.contains("정지중") && normalizedClaim.replace("정지중", "")
                .equals(normalizedSource.replace("정지중", ""))) return true;
        if (hasSupportedTopicEllipsis(normalizedClaim, normalizedSource, question)) return true;
        Matcher subject = REQUIREMENT_SUBJECT.matcher(question);
        if (!subject.find()) return false;
        String topic = conditionTopicKey(subject.group(1));
        if (topic.length() < 2 || CONDITION.matcher(topic).find() || QUANTITY.matcher(topic).find()) return false;
        return normalizedClaim.replace(topic, "").equals(normalizedSource.replace(topic, ""));
    }

    /** 생략된 주어·설명만 허용합니다. 숫자·역할·기간·부정 조건은 원문과 질문에서 확인합니다. */
    private boolean hasSupportedTopicEllipsis(String claim, String source, String question) {
        if (claim.length() < 6 || source.length() <= claim.length() || source.length() - claim.length() > 40) return false;
        List<String> namedServices = RESTRICTION_SERVICE.matcher(question).results()
                .map(match -> serviceName(match.group())).distinct().toList();
        if (namedServices.size() > 1 && RESTRICTION_SERVICE.matcher(source).find()
                && !RESTRICTION_SERVICE.matcher(claim).find()) return false;
        String suppliedContext = claim;
        Matcher condition = CONDITION_SCOPE_MARKER.matcher(source);
        while (condition.find()) {
            if (!suppliedContext.contains(condition.group())) return false;
        }
        for (QuantityFact fact : readFacts(source)) {
            if (readFacts(suppliedContext).stream().noneMatch(provided -> provided.unit().equals(fact.unit())
                    && provided.value().compareTo(fact.value()) == 0)) return false;
        }
        int prefix = 0;
        while (prefix < claim.length() && source.charAt(prefix) == claim.charAt(prefix)) prefix++;
        int suffix = 0;
        while (suffix < claim.length() - prefix
                && source.charAt(source.length() - 1 - suffix) == claim.charAt(claim.length() - 1 - suffix)) suffix++;
        // 하나의 연속된 설명 생략만 비교합니다. 글자를 띄엄띄엄 맞추거나 단어를 추측하지 않습니다.
        if (prefix + suffix != claim.length() || source.length() - claim.length() < 2) return false;
        String omitted = source.substring(prefix, source.length() - suffix);
        return !omitted.matches("(?s).*(?:면|때|중|동안|경우|조건|한정|한해|후(?!불)|전(?!용|화)).*");
    }

    private String conditionTopicKey(String text) {
        String normalized = conditionContext(text)
                .replaceAll("\\s+|[·,，]|(?<![가-힣])(?:및|또는)(?![가-힣])", "")
                .toLowerCase(Locale.ROOT);
        // 같은 상태 조건의 앞뒤 위치만 정리합니다. 조건이 한쪽에 없으면 같아지지 않습니다.
        return normalized.contains("일시정지중")
                ? normalized.replace("일시정지중", "") + "|일시정지중" : normalized;
    }

    private String restrictionAction(String text) {
        String action = text.replaceAll("\\s+", "");
        if (action.matches("번호(?:만|를)?이동")) return "번호이동";
        if (action.equals("미지원")) return "지원";
        if (action.equals("미제공")) return "제공";
        if (action.matches("바꾸(?:실)?|바꿀|바꿔")) return "변경";
        return action.equals("이용") ? "사용" : action;
    }

    /** 필수 서류·서류 예시 목록·독점 조건을 원문 근거와 비교합니다. 기존 면제 검사는 유지합니다. */
    private String removeUnsupportedRequirements(String answer, AnswerMaterials materials, List<String> sources,
            InspectionTrace trace) {
        List<DocumentRequirement> evidence = new ArrayList<>();
        materials.faqs().forEach(faq -> addDocumentEvidence(faq.answer(), faq.question(), sources, evidence));
        materials.sections().forEach(section ->
                addDocumentEvidence(section.content(), materials.question(), sources, evidence));
        List<String> sentences = availabilitySentences(answer);
        List<String> kept = new ArrayList<>();
        boolean removed = false;
        int removedHeadings = 0;
        boolean previousRemoved = false;
        boolean exampleList = false;
        String requiredHeading = null;
        int headingIndex = -1;
        int keptItems = 0;
        for (String sentence : sentences) {
            boolean listItem = DOCUMENT_LIST_ITEM.matcher(sentence).find();
            if (!listItem && requiredHeading != null) {
                if (keptItems == 0) {
                    kept.remove(headingIndex);
                    removedHeadings++;
                }
                requiredHeading = null;
                exampleList = false;
            }
            boolean exampleHeading = DOCUMENT_EXAMPLE_HEADING.matcher(inspect(sentence)).find();
            if (REQUIRED_DOCUMENT_LIST.matcher(inspect(sentence)).find() || exampleHeading) {
                requiredHeading = sentence;
                exampleList = exampleHeading;
                headingIndex = kept.size();
                keptItems = 0;
                kept.add(sentence);
                continue;
            }
            boolean unsupported = readDocumentRequirements(sentence, materials.question(), false,
                    exampleList ? null : requiredHeading)
                    .stream().filter(DocumentRequirement::mandatory)
                    .anyMatch(claim -> evidence.stream().noneMatch(source -> supportsDocument(claim, source)));
            if (exampleList && listItem) {
                String inspected = inspect(sentence);
                Matcher documents = REQUIREMENT_DOCUMENT.matcher(inspected);
                while (documents.find()) {
                    unsupported |= !hasDocumentExampleEvidence(documentName(documents.group()),
                            materials.question() + " " + inspected.substring(0, documents.start()), materials, sources);
                }
            }
            Matcher inlineExamples = INLINE_DOCUMENT_EXAMPLES.matcher(inspect(sentence));
            while (inlineExamples.find()) {
                String tail = inspect(sentence).substring(inlineExamples.end());
                Matcher action = DOCUMENT_EXAMPLE_ACTION.matcher(tail);
                if (!action.find() || isRejectedOrReported(tail.substring(action.end()))) continue;
                Matcher documents = REQUIREMENT_DOCUMENT.matcher(inlineExamples.group("items"));
                while (documents.find()) {
                    unsupported |= !hasDocumentExampleEvidence(documentName(documents.group()),
                            materials.question() + " " + inspect(sentence).substring(0, inlineExamples.start()), materials, sources);
                }
            }
            unsupported |= hasUnsupportedEligibility(inspect(sentence), materials, sources)
                    || hasDocumentPeriodConflict(inspect(sentence), materials, sources);
            Matcher dependent = DEPENDENT_SENTENCE.matcher(inspect(sentence));
            // 삭제한 조건으로 바로 신청할 수 있다는 결론은 남기지 않습니다. 독립된 FAQ 안내는 보존합니다.
            if (!unsupported && previousRemoved && dependent.find()
                    && !hasSourceClaim(inspect(sentence).substring(dependent.end()).strip(), sources)) {
                Matcher conclusion = AVAILABILITY_CLAIM.matcher(sentence);
                unsupported = !QUESTION_END.matcher(sentence).find() && conclusion.find()
                        && !isUnassertedRequirement(sentence.substring(conclusion.end()));
            }
            if (unsupported) {
                trace.removed(GuardRule.DOCUMENT_REQUIREMENT);
                removed = true;
                previousRemoved = true;
                continue;
            }
            if (requiredHeading != null && listItem) keptItems++;
            kept.add(sentence);
            previousRemoved = false;
        }
        if (requiredHeading != null && keptItems == 0) {
            kept.remove(headingIndex);
            removedHeadings++;
        }
        if (removed) trace.removed(removedHeadings, GuardRule.EMPTY_HEADING);
        return removed ? String.join("\n", kept) : answer;
    }

    private void addDocumentEvidence(String text, String question, List<String> sources,
            List<DocumentRequirement> evidence) {
        if (!StringUtils.hasText(text)) return;
        String requiredHeading = null;
        for (String sentence : providedSourceSentences(text, sources)) {
            if (REQUIRED_DOCUMENT_LIST.matcher(inspect(sentence)).find()) {
                requiredHeading = sentence;
                continue;
            }
            if (!DOCUMENT_LIST_ITEM.matcher(sentence).find()) requiredHeading = null;
            evidence.addAll(readDocumentRequirements(sentence, question, true, requiredHeading));
        }
    }

    private List<DocumentRequirement> readDocumentRequirements(String sentence, String question,
            boolean source, String requiredHeading) {
        String inspected = inspect(sentence).replaceAll(
                "(?<=[증서장])(?:과|와)(?=[가-힣]{2,}(?:증명서|확인서|허가서|계약서))", " ");
        List<DocumentRequirement> requirements = new ArrayList<>();
        if (QUESTION_END.matcher(inspected).find()) return requirements;
        String requiredList = requiredHeading == null && INLINE_REQUIRED_DOCUMENT_LIST.matcher(inspected).find()
                ? inspected : requiredHeading;
        Matcher documents = REQUIREMENT_DOCUMENT.matcher(inspected);
        while (documents.find()) {
            String tail = inspected.substring(documents.end());
            Matcher obligation = (source ? SOURCE_DOCUMENT_OBLIGATION : DOCUMENT_OBLIGATION).matcher(tail);
            String action;
            String beforeObligation = "";
            if (obligation.find() && obligation.start() <= 96) {
                if (isUnassertedRequirement(tail.substring(obligation.end()))) continue;
                action = obligation.group();
                beforeObligation = tail.substring(0, obligation.start());
            } else if (source && documents.group().replaceAll("\\s+", "").matches("본인(?:인증|확인)|법정대리인(?:의)?동의")
                    && tail.matches("^\\s*(?:후|을\\s*거친\\s*후).*")) {
                action = "필수";
            } else if (requiredList != null) {
                action = requiredList.contains("제출") ? "제출" : "필수";
            } else {
                continue;
            }
            if (DOCUMENT_NOT_ASSERTED.matcher(beforeObligation).find()) continue;
            DocumentObligation kind = action.startsWith("확인") ? DocumentObligation.VERIFY
                    : action.startsWith("제출") || action.startsWith("첨부") || action.startsWith("제시") || action.startsWith("받아")
                    ? DocumentObligation.SUBMIT : action.startsWith("준비") || action.startsWith("지참")
                    || action.startsWith("구비") ? DocumentObligation.PREPARE : DocumentObligation.REQUIRED;
            String name = documentName(documents.group());
            boolean mandatory = requiredList != null || action.contains("야") || action.contains("필수")
                    || action.contains("필요") || beforeObligation.contains("반드시")
                    || beforeObligation.matches(".*(?:^|\\s)꼭\\s*.*") || inspected.substring(0, documents.start()).contains("반드시")
                    || inspected.substring(0, documents.start()).matches(".*(?:^|\\s)꼭\\s*.*");
            String scope = question + " " + inspected.substring(0, documents.start());
            requirements.add(new DocumentRequirement(name, kind, mandatory,
                    DOCUMENT_CHOICE.matcher(inspected).find(), scope));
        }
        return requirements;
    }

    private String documentName(String name) {
        return name.replaceAll("\\s+", "")
                .replace("본인확인", "본인인증").replace("법정대리인의", "법정대리인")
                .replace("보호자의", "보호자").replace("가족관계를증명하는서류", "가족관계확인서류")
                .replace("가족관계증명서류", "가족관계확인서류");
    }

    private boolean hasDocumentExampleEvidence(String document, String scope,
            AnswerMaterials materials, List<String> sources) {
        for (var faq : materials.faqs()) {
            if (hasDocumentMention(document, scope, faq.question(), faq.answer(), sources)) return true;
        }
        for (var section : materials.sections()) {
            if (hasDocumentMention(document, scope, section.title(), section.content(), sources)) return true;
        }
        return false;
    }

    private boolean hasDocumentMention(String document, String scope, String sourceScope,
            String text, List<String> sources) {
        if (!StringUtils.hasText(text)) return false;
        for (String source : sources) {
            if (!inspect(text).contains(source) || !hasSameRequirementScope(scope, sourceScope + " " + source)) continue;
            Matcher documents = REQUIREMENT_DOCUMENT.matcher(source);
            while (documents.find()) {
                if (documentName(documents.group()).equals(document)) return true;
            }
        }
        return false;
    }

    private boolean supportsDocument(DocumentRequirement claim, DocumentRequirement source) {
        if (!claim.document().equals(source.document()) || !hasSameRequirementScope(claim.scope(), source.scope())) return false;
        // 선택 가능한 서류 중 하나를 유일한 필수 서류로 바꾸지 않습니다.
        if (claim.mandatory() && source.alternative() && !claim.alternative()) return false;
        if (claim.obligation() == DocumentObligation.VERIFY) return true;
        if (source.obligation() == DocumentObligation.VERIFY) return false;
        return claim.obligation() != DocumentObligation.SUBMIT
                || source.obligation() == DocumentObligation.SUBMIT || source.obligation() == DocumentObligation.REQUIRED;
    }

    private boolean hasSameRequirementScope(String claim, String source) {
        for (List<String> axis : REQUIREMENT_SCOPES) {
            List<String> conditions = axis.stream().filter(source::contains).toList();
            List<String> claimed = axis.stream().filter(value -> claim.replace("성인 요금제", "요금제")
                    .replace("성인요금제", "요금제").contains(value)).toList();
            if (conditions.size() == 1 && !claimed.isEmpty() && !claimed.contains(conditions.getFirst())) return false;
        }
        // 명시적인 업무가 양쪽에 하나씩 있을 때만 비교합니다. 생략·복합 문의의 업무를 추측하지 않습니다.
        List<String> claimedOperations = readBusinessScopes(inspect(claim), true);
        List<String> sourceOperations = readBusinessScopes(inspect(source), true);
        if (claimedOperations.size() == 1 && sourceOperations.size() == 1
                && !claimedOperations.getFirst().equals(sourceOperations.getFirst())) return false;
        return true;
    }

    private boolean hasUnsupportedEligibility(String sentence, AnswerMaterials materials, List<String> sources) {
        if (QUESTION_END.matcher(sentence).find()) return false;
        String question = materials.question();
        List<FrequencyClaim> claims = readFrequencyClaims(sentence, question);
        List<FrequencyClaim> frequencies = List.of();
        if (!claims.isEmpty()) {
            List<FrequencyClaim> evidence = readFrequencyEvidence(materials, sources);
            // 기간·횟수·행위·대상을 함께 비교합니다. 다른 FAQ의 같은 숫자는 근거가 아닙니다.
            if (claims.stream().anyMatch(claim -> evidence.stream().noneMatch(source -> matchesFrequencyClaim(claim, source, question)) && evidence.stream()
                    .anyMatch(source -> source.action().equals(claim.action())
                            && (source.topic().equals(claim.topic()) || claim.explicitTarget())))) return true;
            frequencies = claims.stream().filter(claim -> evidence.stream().anyMatch(source -> matchesFrequencyClaim(claim, source, question))).toList();
        }
        Matcher exclusive = EXCLUSIVE_REQUIREMENT.matcher(sentence);
        while (exclusive.find()) {
            String tail = sentence.substring(exclusive.end());
            if (isUnassertedRequirement(tail) || isQuantityMagnitude(sentence, exclusive.start())) continue;
            String context = sentence.substring(0, exclusive.start());
            boolean supported = sources.stream().anyMatch(source -> {
                if (QUESTION_END.matcher(source).find()) return false;
                Matcher condition = EXCLUSIVE_REQUIREMENT.matcher(source);
                while (condition.find()) {
                    String after = source.substring(condition.end());
                    if (tail.matches("(?s).*(?:수\\s*없|불가|제한|허용되지).*")
                            && after.matches("(?s).*(?:수\\s*없|불가|제한|허용되지).*")) {
                        String claimTail = conditionTopicKey(tail);
                        boolean missingScope = CONDITION_SCOPE_MARKER.matcher(conditionTopicKey(after)).results()
                                .anyMatch(marker -> !claimTail.contains(marker.group()));
                        if (missingScope) continue;
                    }
                    if (!isQuantityMagnitude(source, condition.start())
                            && matchesRequirementRestriction(context, exclusive.group(1),
                            source.substring(0, condition.start()), condition.group(1), question)
                            && !isUnassertedRequirement(after)) return true;
                }
                return false;
            });
            if (!supported && !hasSupportedFrequency(context, exclusive.group(1), frequencies)
                    && !hasSupportedConnectionSetting(context, exclusive.group(), exclusive.group(1), materials, sources)) return true;
        }
        Matcher mandatory = MANDATORY_ELIGIBILITY.matcher(sentence);
        while (mandatory.find()) {
            String tail = sentence.substring(mandatory.end());
            if (isUnassertedRequirement(tail)) continue;
            String required = mandatory.group().replaceAll("\\s+", "");
            boolean supported = sources.stream().filter(source -> !QUESTION_END.matcher(source).find()).anyMatch(source -> {
                Matcher condition = MANDATORY_ELIGIBILITY.matcher(source);
                while (condition.find()) {
                    String after = source.substring(condition.end());
                    if (required.equals(condition.group().replaceAll("\\s+", ""))
                            && !isUnassertedRequirement(after)) return true;
                }
                return false;
            });
            if (!supported) return true;
        }
        return false;
    }

    /** 이용 자격과 분리해, 같은 제공 자료의 실제 데이터 끄기 설정으로 선택하는 연결 방식만 보존합니다. */
    private boolean hasSupportedConnectionSetting(String context, String expression, String action,
            AnswerMaterials materials, List<String> sources) {
        if (!action.matches("사용|이용|연결") || !expression.matches("(?s).*(?:할\\s*수\\s*있|이?\\s*가능)$")) return false;
        Matcher setting = CONNECTION_SETTING_CONTEXT.matcher(context.strip());
        if (!setting.matches()) return false;
        String name = setting.group("setting").replaceAll("\\s+", "");
        List<String> bodies = new ArrayList<>();
        materials.faqs().forEach(faq -> bodies.add(faq.answer()));
        materials.sections().forEach(section -> bodies.add(section.content()));
        for (String body : bodies) {
            if (!StringUtils.hasText(body)) continue;
            List<String> provided = sources.stream().filter(source -> inspect(body).contains(source)).toList();
            if (provided.stream().noneMatch(source -> !QUESTION_END.matcher(source).find()
                    && WIFI_SETTING_SOURCE.matcher(source).find()
                    && (!readRestrictionClaims(source, null).isEmpty()
                    || readAvailabilityClaims(source, null, -1).stream()
                            .anyMatch(claim -> claim.state() != AvailabilityState.UNKNOWN)))) continue;
            for (String source : provided) {
                if (QUESTION_END.matcher(source).find()) continue;
                Matcher off = DATA_CONNECTION_OFF.matcher(source);
                if (off.lookingAt() && name.equals(off.group("setting").replaceAll("\\s+", ""))
                        && CONNECTION_SETTING_EFFECT.matcher(source.substring(off.end())).matches()) return true;
            }
        }
        return false;
    }

    private boolean isUnassertedRequirement(String tail) {
        String content = tail.replaceFirst("^\\s*(?:입니다|예요|합니다|해요|습니다|어요)\\s*", "");
        return REPORTED_OR_HYPOTHETICAL.matcher(content).find()
                || QUOTED_SECRET_REQUEST.matcher(tail).find()
                || DOCUMENT_NOT_ASSERTED.matcher(content).lookingAt()
                || DENIED_DOCUMENT_OBLIGATION.matcher(content).matches();
    }

    private boolean matchesRequirementRestriction(String context, String action,
            String sourceContext, String sourceAction, String question) {
        if (!action.equals(sourceAction)) return false;
        String claim = requirementRestriction(localConditionContext(context), action);
        String source = requirementRestriction(localConditionContext(sourceContext), sourceAction);
        // 지시 대명사 주어는 일반 생략 비교보다 먼저 확인해, 다른 서비스의 제한으로 연결하지 않습니다.
        if (context.strip().matches("^(?:해당|관련)\\s*서비스(?:는|은)?\\s+.*")) {
            Matcher subject = REQUIREMENT_SUBJECT.matcher(sourceContext);
            return subject.find() && StringUtils.hasText(question)
                    && question.replaceAll("\\s+", "").contains(subject.group(1).replaceAll("\\s+", ""))
                    && claim.equals(requirementRestriction(sourceContext.substring(subject.end()), sourceAction));
        }
        return claim.equals(source) || sameConditionTopic(claim, source, question);
    }

    /** 금액의 배수 단위와 그 뒤의 실제 한정 조사를 구분합니다. */
    private boolean isQuantityMagnitude(String sentence, int offset) {
        Matcher quantity = QUANTITY.matcher(sentence);
        while (quantity.find()) {
            if (quantity.group("money") != null && quantity.start() <= offset && offset < quantity.end()) return true;
        }
        return false;
    }

    private boolean matchesFrequencyClaim(FrequencyClaim claim, FrequencyClaim source, String question) {
        return claim.matches(source) || claim.period().equals(source.period())
                && claim.interval().equals(source.interval()) && claim.count().equals(source.count())
                && claim.action().equals(source.action()) && sameConditionTopic(claim.topic(), source.topic(), question);
    }

    private boolean hasSupportedFrequency(String context, String action, List<FrequencyClaim> frequencies) {
        return frequencies.stream().anyMatch(claim -> claim.end() == context.stripTrailing().length()
                && claim.action().equals(restrictionAction(action)));
    }

    private List<FrequencyClaim> readFrequencyEvidence(AnswerMaterials materials, List<String> sources) {
        List<FrequencyClaim> evidence = new ArrayList<>();
        materials.faqs().forEach(faq -> providedSourceSentences(faq.answer(), sources).stream()
                .forEach(source -> evidence.addAll(readFrequencyClaims(source, faq.question()))));
        materials.sections().forEach(section -> providedSourceSentences(section.content(), sources).stream()
                .forEach(source -> evidence.addAll(readFrequencyClaims(source, section.title()))));
        return evidence;
    }

    private List<FrequencyClaim> readFrequencyClaims(String sentence, String question) {
        List<FrequencyClaim> claims = new ArrayList<>();
        if (QUESTION_END.matcher(sentence).find()) return claims;
        Matcher frequency = REQUIREMENT_FREQUENCY.matcher(sentence);
        int previousEnd = 0;
        while (frequency.find()) {
            String before = sentence.substring(previousEnd, frequency.start());
            String tail = sentence.substring(frequency.end());
            Matcher permission = FREQUENCY_PERMISSION.matcher(tail);
            previousEnd = frequency.end();
            if (!permission.find() || isUnassertedScope(tail.substring(permission.end()))) continue;
            previousEnd += permission.end();
            Matcher prefixAction = FREQUENCY_PREFIX_ACTION.matcher(before);
            String action = permission.group("action");
            if (prefixAction.find()) {
                if (action == null) action = prefixAction.group(1);
                before = before.substring(0, prefixAction.start());
            }
            if (action == null) continue;
            String topic = restrictionTopic(before + " " + (permission.group("target") == null ? "" : permission.group("target")));
            // 앞 절까지 섞인 문맥을 명확한 서비스명으로 단정하지 않습니다.
            boolean explicitTarget = StringUtils.hasText(permission.group("target"))
                    || !topic.isEmpty() && !before.matches("(?s).*[,;.!?。！？\\n].*");
            if (topic.isEmpty() && StringUtils.hasText(question)) {
                Matcher subject = REQUIREMENT_SUBJECT.matcher(inspect(question));
                List<String> services = RESTRICTION_SERVICE.matcher(question).results()
                        .map(match -> serviceName(match.group())).distinct().toList();
                boolean hasSubject = subject.find();
                topic = restrictionTopic(hasSubject ? subject.group(1)
                        : services.size() == 1 ? services.getFirst() : question);
                explicitTarget = hasSubject || services.size() == 1;
                if (!explicitTarget) {
                    Matcher asked = RESTRICTION_QUESTION.matcher(question);
                    while (asked.find()) {
                        if (!restrictionAction(asked.group(1)).equals(restrictionAction(action))) continue;
                        topic = restrictionTopic(question.substring(0, asked.start()));
                        explicitTarget = !topic.isEmpty();
                        break;
                    }
                }
            }
            if (topic.isEmpty()) continue;
            String period = frequency.group("daily") != null || "일".equals(frequency.group("unit")) ? "일" : "개월";
            BigDecimal interval = frequency.group("interval") == null ? BigDecimal.ONE
                    : new BigDecimal(frequency.group("interval"));
            claims.add(new FrequencyClaim(period, interval, new BigDecimal(frequency.group("count")),
                    topic, restrictionAction(action), frequency.end(), explicitTarget));
        }
        return claims;
    }

    private String requirementRestriction(String context, String action) {
        String topic = availabilityTopic(conditionContext(context))
                .replaceAll("(?:해당|관련)\\s*서비스\\s*", "")
                .replaceAll("(?:\\s+및\\s+|(?<=[가-힣])(?:과|와)(?=\\s))", " ")
                .replaceAll("\\s+", " ").strip();
        return topic + "|" + action;
    }

    /** 같은 대상·행위·명시된 조건의 상반된 안내만 비교합니다. 다른 조건을 추측해 합치지 않습니다. */
    private String removeContradictoryAvailability(String answer, AnswerMaterials materials, List<String> sources,
            InspectionTrace trace) {
        List<String> sentences = availabilitySentences(answer);
        List<AvailabilityClaim> claims = new ArrayList<>();
        for (int index = 0; index < sentences.size(); index++) {
            claims.addAll(readAvailabilityClaims(sentences.get(index), materials.question(), index));
        }
        if (claims.isEmpty()) return answer;
        List<AvailabilityClaim> evidence = new ArrayList<>();
        materials.faqs().forEach(faq -> addAvailabilityEvidence(faq.answer(), faq.question(), sources, evidence));
        materials.sections().forEach(section ->
                addAvailabilityEvidence(section.content(), materials.question(), sources, evidence));
        List<Integer> removed = new ArrayList<>();
        for (String key : claims.stream().map(AvailabilityClaim::key).distinct().toList()) {
            List<AvailabilityClaim> group = claims.stream().filter(claim -> claim.key().equals(key)).toList();
            if (group.stream().map(AvailabilityClaim::state).distinct().count() < 2) continue;
            boolean supportedAllowed = evidence.stream().anyMatch(claim -> claim.key().equals(key)
                    && claim.state() == AvailabilityState.ALLOWED);
            boolean supportedDenied = evidence.stream().anyMatch(claim -> claim.key().equals(key)
                    && claim.state() == AvailabilityState.DENIED);
            AvailabilityState retained = supportedAllowed != supportedDenied
                    ? (supportedAllowed ? AvailabilityState.ALLOWED : AvailabilityState.DENIED)
                    : AvailabilityState.UNKNOWN;
            // 근거가 없거나 자료 자체가 충돌하면 어느 확정 주장도 선택하지 않습니다.
            group.stream().filter(claim -> claim.state() != retained)
                    .map(AvailabilityClaim::sentenceIndex).forEach(removed::add);
        }
        if (removed.isEmpty()) return answer;
        List<String> kept = new ArrayList<>();
        for (int index = 0; index < sentences.size(); index++) {
            if (removed.contains(index)) {
                trace.removed(GuardRule.CONTRADICTORY_AVAILABILITY);
                continue;
            }
            String sentence = sentences.get(index);
            // 바로 앞에서 삭제한 판단과 같은 행위의 실행 지시만 함께 제거합니다. 직접 확인·문의 안내는 보존합니다.
            String inspected = inspect(sentence);
            Matcher dependent = DEPENDENT_SENTENCE.matcher(inspected);
            Matcher instruction = AVAILABILITY_INSTRUCTION.matcher(inspected);
            int previousIndex = index - 1;
            if (dependent.find() && removed.contains(previousIndex) && instruction.find()
                    && claims.stream().anyMatch(claim -> claim.sentenceIndex() == previousIndex
                            && claim.key().endsWith("|" + instruction.group(1)))
                    && !hasSourceClaim(inspected.substring(dependent.end()).strip(), sources)) {
                trace.removed(GuardRule.DEPENDENT_CONCLUSION);
                continue;
            }
            kept.add(sentence);
        }
        return String.join("\n", kept);
    }

    private void addAvailabilityEvidence(String text, String question, List<String> sources,
            List<AvailabilityClaim> evidence) {
        if (!StringUtils.hasText(text)) return;
        for (String sentence : splitSentences(text)) {
            // 기존 근거 정리를 재사용해 검색 문서에 삽입된 모델 지시를 사실 근거로 쓰지 않습니다.
            if (sources.contains(inspect(sentence))) {
                for (String statement : availabilitySentences(sentence)) {
                    evidence.addAll(readAvailabilityClaims(statement, question, -1));
                }
            }
        }
    }

    private List<String> availabilitySentences(String text) {
        // 근거 수집·수치·이용 제한 검사에 같은 문장 경계를 사용합니다.
        return splitSentences(text);
    }

    private List<AvailabilityClaim> readAvailabilityClaims(String sentence, String question, int sentenceIndex) {
        String inspected = inspect(sentence);
        List<AvailabilityClaim> claims = new ArrayList<>();
        if (QUESTION_END.matcher(inspected).find()) return claims;
        Matcher matcher = AVAILABILITY_CLAIM.matcher(inspected);
        int previousEnd = 0;
        while (matcher.find()) {
            String context = inspected.substring(previousEnd, matcher.start());
            previousEnd = matcher.end();
            String tail = inspected.substring(matcher.end());
            if (REPORTED_OR_HYPOTHETICAL.matcher(tail).find() || QUOTED_SECRET_REQUEST.matcher(tail).find()) continue;
            String topic = availabilityTopic(context);
            String action = matcher.group(1);
            // 대상이 완전히 생략됐을 때만 동일 행위의 질문을 사용합니다. 부분 조건을 질문으로 채우지 않습니다.
            if (topic.isEmpty() && StringUtils.hasText(question)) {
                Matcher asked = AVAILABILITY_QUESTION.matcher(inspect(question));
                if (asked.find() && asked.group(1).equals(action)) {
                    topic = availabilityTopic(inspect(question).substring(0, asked.start()));
                }
            }
            if (topic.isEmpty()) continue;
            String predicate = matcher.group(2).replaceAll("\\s+", "");
            AvailabilityState state = predicate.contains("여부") || predicate.contains("있는지")
                    || predicate.contains("가능한지") ? AvailabilityState.UNKNOWN
                    : predicate.contains("없") || predicate.contains("불가") || predicate.contains("않")
                    ? AvailabilityState.DENIED : AvailabilityState.ALLOWED;
            claims.add(new AvailabilityClaim(topic + "|" + action, state, sentenceIndex));
        }
        return claims;
    }

    private String availabilityTopic(String context) {
        String topic = context.replaceFirst("^(?:(?:네|아니요|하지만|그러나|반면|따라서|다만|또한|즉)[,，\\s]*)+", "")
                .replaceAll("에\\s*대한\\s+", " ")
                .replaceAll("[,，;:：]", " ").strip();
        List<String> words = new ArrayList<>();
        for (String word : topic.split("\\s+")) {
            if (!word.isEmpty()) words.add(word.replaceFirst(
                    "^([가-힣a-zA-Z0-9-]{2,}?)(?:에서는|에서도|에는|에도|에서|으로|은|는|을|를|이|가|에|로)$", "$1"));
        }
        // 단어 순서·수치·대상·조건을 보존해 서로 다른 역할이나 범위를 같은 주장으로 취급하지 않습니다.
        return String.join(" ", words);
    }

    private List<String> splitSentences(String answer) {
        List<String> sentences = new ArrayList<>();
        // 표준 문장 경계를 사용합니다. 입력용 이모지·인사말 정리를 답변에 다시 적용하지 않습니다.
        for (String line : answer.split("\\R")) {
            BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.KOREAN);
            iterator.setText(line);
            int start = iterator.first();
            for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
                String sentence = line.substring(start, end).strip();
                if (!sentence.isEmpty()) addAnswerClauses(sentence, sentences, false, null);
            }
        }
        return sentences;
    }

    /** 독립된 질문과 조회 약속의 연결 절을 나눕니다. 인용문 내부의 쉼표는 유지합니다. */
    private void addAnswerClauses(String sentence, List<String> sentences, boolean queryClauses,
            Predicate<String> independentQueryFact) {
        boolean mixedQuery = independentQueryFact != null;
        boolean question = QUESTION_END.matcher(inspect(sentence)).find()
                || queryClauses && hasFollowUpInformationRequest(inspect(sentence));
        Matcher query = QUERY_PROMISE.matcher(inspect(sentence));
        boolean queryPromise = false;
        while (queryClauses && query.find()) {
            queryPromise |= query.group().endsWith("드리고") || query.group().endsWith("드리며")
                    || ACCOUNT_RECORD.matcher(query.group()).lookingAt();
        }
        if (!BOT_QUERY_SUBJECT.matcher(sentence).find()
                && (QUERY_SOURCE_ATTRIBUTION.matcher(sentence).find()
                || STATIC_SOURCE.matcher(sentence).find() && QUERY_SOURCE_REPORT.matcher(inspect(sentence)).find())) queryPromise = false;
        if (!question && !queryPromise && !mixedQuery
                && !AVAILABILITY_SENTENCE_BOUNDARY.matcher(sentence).find()) {
            sentences.add(sentence);
            return;
        }
        int start = 0;
        char closingQuote = 0;
        for (int index = 0; index < sentence.length(); index++) {
            char character = sentence.charAt(index);
            if (index > 0 && sentence.charAt(index - 1) == '\\') continue;
            if (closingQuote != 0) {
                if (character == closingQuote) closingQuote = 0;
                continue;
            }
            if ("'\"‘“`＇＂「『".indexOf(character) >= 0) {
                closingQuote = switch (character) {
                    case '‘' -> '’';
                    case '“' -> '”';
                    case '「' -> '」';
                    case '『' -> '』';
                    default -> character;
                };
                continue;
            }
            boolean separator = ",，;；".indexOf(character) >= 0
                    || Character.isWhitespace(character) || Character.isSpaceChar(character);
            String prefix = inspect(sentence.substring(start, index));
            // 표준 경계가 숫자·소문자로 시작하는 다음 문장을 합쳐도 인용 밖의 독립 문장은 나눕니다.
            // 소수점·전화번호·목록 번호는 경계로 보지 않습니다.
            if (index > start && Character.isWhitespace(character)
                    && ".!?。！？".indexOf(sentence.charAt(index - 1)) >= 0
                    && !prefix.matches("[0-9]+[.)]")) {
                sentences.add(sentence.substring(start, index).strip());
                start = index + 1;
                while (start < sentence.length() && Character.isWhitespace(sentence.charAt(start))) start++;
                index = start - 1;
                continue;
            }
            boolean asserted = ASSERTED_CLAUSE_END.matcher(prefix).find();
            Matcher factConnective = QUERY_FACT_CONNECTIVE_END.matcher(prefix);
            boolean factBoundary = mixedQuery && factConnective.find();
            boolean previousQueryBoundary = queryPromise && (",，;；".indexOf(character) >= 0 && asserted
                    || QUERY_CONNECTIVE_END.matcher(prefix).find());
            boolean queryBoundary = previousQueryBoundary || mixedQuery
                    && (",，;；".indexOf(character) >= 0 && asserted || factBoundary);
            if (!separator || !(question && asserted || queryBoundary)) continue;
            int next = index + 1;
            while (next < sentence.length() && (",，;；".indexOf(sentence.charAt(next)) >= 0
                    || Character.isWhitespace(sentence.charAt(next)) || Character.isSpaceChar(sentence.charAt(next)))) next++;
            String tail = sentence.substring(next);
            // '완료했습니다 라는 문구' 같은 인용 설명·조건을 실제 완료 주장으로 바꾸지 않습니다.
            if (tail.isBlank() || REPORTED_OR_HYPOTHETICAL.matcher(inspect(tail)).find()) continue;
            if (mixedQuery) {
                int boundary = index;
                if (QUERY_PROMISE.matcher(sentence).results()
                        .anyMatch(promise -> promise.start() < boundary && promise.end() > boundary)) continue;
            }
            // 한 조회 계획의 뒷부분만 남기지 않고, 독립된 절차 안내와 나뉠 때만 분리합니다.
            if (queryBoundary && (inspect(tail).startsWith("있")
                    || QUERY_PROMISE.matcher(prefix).find() && QUERY_CONTINUATION.matcher(inspect(tail)).find())) continue;
            // 동일 문장의 생략된 가격 항목은 함께 검사해, 질문을 분리하며 요금제 연결을 잃지 않습니다.
            String inspectedTail = inspect(tail);
            Matcher continuedPrice = PLAN_PRICE_FACT.matcher(inspectedTail);
            if (continuedPrice.lookingAt() && continuedPrice.group("money") != null
                    && inspectedTail.substring(continuedPrice.end()).matches("^\\s*(?:입니다|예요|이에요)[,，;；\\s].*")
                    && PLAN_PRICE_FACT.matcher(inspect(sentence.substring(start, index))).find()) continue;
            String clause = sentence.substring(start, index).strip();
            if (factBoundary && !previousQueryBoundary) {
                String stem = factConnective.group("stem");
                char last = stem.charAt(stem.length() - 1);
                // 열린 음절의 '-하고/-되고/-기고'는 '-합니다/-됩니다/-깁니다'로 끝맺습니다.
                String ending = (last - '가') % 28 == 0
                        ? stem.substring(0, stem.length() - 1) + (char) (last + 17) + "니다."
                        : stem + "습니다.";
                clause = prefix.substring(0, factConnective.start()) + ending;
            }
            // 새 경계로 복구하는 절만 독립 근거를 확인합니다. 기존 질문·조회 절 분리는 유지합니다.
            if (mixedQuery && !previousQueryBoundary && !independentQueryFact.test(clause)) continue;
            sentences.add(clause);
            start = next;
            index = next - 1;
        }
        String remaining = sentence.substring(start).strip();
        if (!remaining.isEmpty()) sentences.add(remaining);
    }

    private String inspect(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).replaceAll("\\p{Cf}", "").strip();
    }

    private enum AnswerScopeKind { DEVICE_PLAN, ESIM_COMPATIBILITY, SIM_COMPATIBILITY, SHARING_COMPATIBILITY, SHARING_APPLICATION, RETENTION, SERVICE_FEE, PLAN_PRICE, BUSINESS_OPERATION }

    private record ScopedAnswerFact(AnswerScopeKind kind, String target, QuantityFact quantity) {
    }

    private enum DocumentObligation { VERIFY, PREPARE, SUBMIT, REQUIRED }

    private record DocumentRequirement(String document, DocumentObligation obligation, boolean mandatory,
            boolean alternative, String scope) {
    }

    private enum AvailabilityState { ALLOWED, DENIED, UNKNOWN }

    private record AvailabilityClaim(String key, AvailabilityState state, int sentenceIndex) {
    }

    private record FrequencyClaim(String period, BigDecimal interval, BigDecimal count,
            String topic, String action, int end, boolean explicitTarget) {
        private boolean matches(FrequencyClaim other) {
            return period.equals(other.period) && interval.equals(other.interval) && count.equals(other.count)
                    && topic.equals(other.topic) && action.equals(other.action);
        }
    }

    private enum QuantityBound { EXACT, MAXIMUM, MINIMUM, UNCERTAIN }

    private record ScopedQuantity(QuantityFact quantity, String target, String condition) {
    }

    private record QuantityFact(String metric, BigDecimal value, String unit, QuantityBound bound) {

        private boolean accepts(BigDecimal candidate) {
            int comparison = candidate.compareTo(value);
            return switch (bound) {
                case MAXIMUM -> comparison <= 0;
                case MINIMUM -> comparison >= 0;
                case UNCERTAIN -> true;
                default -> comparison == 0;
            };
        }
    }

    private record DocumentCondition(String document, boolean waived) {
    }
}
