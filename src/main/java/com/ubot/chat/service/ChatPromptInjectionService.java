package com.ubot.chat.service;

import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 사용자 질문에서 명확한 모델 지침 조작 문구를 검사합니다. */
@Slf4j
@Service
public class ChatPromptInjectionService {

	// 금칙어 검사와 같은 Unicode FORMAT 범위의 숨은 문자를 검사용 사본에서 제거합니다.
	private static final Pattern INVISIBLE_CHARACTERS = Pattern.compile("\\p{Cf}");
	private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
	private static final Pattern SPACED_SYLLABLES = Pattern.compile(
			"(?<![\\p{L}\\p{N}_])(?:[\\p{IsHangul}]\\s+){2,39}[\\p{IsHangul}](?![\\p{L}\\p{N}_])");
	private static final Pattern OBFUSCATED_IGNORE = Pattern.compile("\\b[i1][g9]n[o0]r[e3]\\b", Pattern.CASE_INSENSITIVE);
	private static final Pattern INLINE_COMMENT = Pattern.compile("(?<=[\\p{L}])/\\*[^\\r\\n]{0,120}?\\*/(?=[\\p{L}])");
	private static final Pattern DECODE_EXECUTION = Pattern.compile(
			"(?:풀어서|풀고|해석(?:하고|해서)|복원(?:하고|해서)|디코딩(?:하고|해서)|해독(?:하고|해서))"
					+ "\\s*(?:따라|실행|적용)", Pattern.CASE_INSENSITIVE);
	private static final Pattern BASE64_INSTRUCTION = Pattern.compile(
			"(?i:base64)[^.!?;。！？\\r\\n]{0,40}?[:：]\\s*(?<encoded>[a-zA-Z0-9+/]{8,1024}={0,2})(?![a-zA-Z0-9+/=])");
	private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");
	private static final Pattern PERCENT_ENCODED = Pattern.compile("(?:%[0-9a-fA-F]{2}){1,1024}");
	private static final String STRUCTURED_CONTROL_MESSAGE =
			"(?:(?:\\[im_start\\]|<\\|im_start\\|>)\\s*(?:system|developer|assistant)\\b"
					+ "[\\s\\S]{1,2000}?(?:\\[im_end\\]|<\\|im_end\\|>)"
					+ "|(?:</user>\\s*)?<(?:system|developer)>[\\s\\S]{1,2000}?</(?:system|developer)>"
					+ "|\\{(?=[^{}]{0,2000}[\"']role[\"']\\s*:\\s*[\"'](?:system|developer|assistant)[\"'])[^{}]{1,2000}\\}"
					+ "|\\[(?:도구\\s*결과|이전\\s*ai|정책\\s*공지)\\][^.!?;。！？\\r\\n]{1,400}[.!?;。！？]?)";
	private static final Pattern CONTROL_MESSAGE = Pattern.compile(STRUCTURED_CONTROL_MESSAGE, Pattern.CASE_INSENSITIVE);
	private static final Pattern ADDITIONAL_REQUEST_LABEL = Pattern.compile("추가\\s*요청:\\s*$");
	private static final String KOREAN_POSSIBILITY_REQUEST =
			"수\\s*있(?:어(?:요)?|나요|니|을까요)(?![\\p{L}\\p{N}_])";
	private static final String KOREAN_GIVE_REQUEST =
			"(?:줘|주세요|(?:줄래(?:요)?|주실래(?:요)?)(?![\\p{L}\\p{N}_])"
					+ "|(?:줄|주실)\\s*" + KOREAN_POSSIBILITY_REQUEST + ")";
	private static final String KOREAN_DISCLOSURE_PARTICLE = "(?:을|를|은|는|도|만|까지)?";
	private static final String KOREAN_DISCLOSURE_MODIFIERS =
			"(?:(?:그대로|전부|모두|전체|원문|내용|값|같이|일부"
					+ "|(?:첫|마지막)\\s*(?:문장|줄)|글자\\s*하나씩"
					+ "|(?:영어|한국어|base64)(?:로|으로)?)" + KOREAN_DISCLOSURE_PARTICLE + "\\s*){0,4}"
					+ "(?:https?://[^\\s<>]{1,200}\\s*(?:로|으로)?\\s*)?";
	private static final String KOREAN_EXTENDED_GIVE_REQUEST =
			"(?:" + KOREAN_GIVE_REQUEST + "|줘요|달라|다오)";
	private static final String KOREAN_IMPERATIVE_ACTION_REQUEST =
			"(?:해(?:라|\\s*" + KOREAN_EXTENDED_GIVE_REQUEST + ")?"
					+ "|하(?:라|세요))";
	private static final String KOREAN_EXPLICIT_ACTION_REQUEST =
			"(?:" + KOREAN_IMPERATIVE_ACTION_REQUEST + "|할\\s*" + KOREAN_POSSIBILITY_REQUEST + ")";
	private static final String KOREAN_ASSERTION_ACTION_REQUEST =
			"(?:(?:말|답|응답|표시|안내|설명|주장)\\s*)?" + KOREAN_EXPLICIT_ACTION_REQUEST
					+ "(?![\\p{L}\\p{N}_])";
	private static final String KOREAN_POLICY_DISABLE_COMMAND =
			"(?:(?:해제|우회|비활성화|무시|폐기|생략|제외)" + KOREAN_IMPERATIVE_ACTION_REQUEST
					+ "|(?:꺼|풀어|빼|버려|지워)(?:\\s*" + KOREAN_EXTENDED_GIVE_REQUEST + "|라)?"
					+ "|끄도록\\s*" + KOREAN_IMPERATIVE_ACTION_REQUEST
					+ "|무효(?:야|다|입니다)"
					+ "|(?:끄고|풀고|빼고|(?:해제|우회|비활성화|무시|폐기|생략|제외)하고)\\s*"
					+ "(?:(?:그냥|바로)\\s*)?(?:답|대답|응답)" + KOREAN_IMPERATIVE_ACTION_REQUEST + ")(?![\\p{L}\\p{N}_])";
	private static final String KOREAN_POLICY_DISABLE_REQUEST =
			"(?:" + KOREAN_POLICY_DISABLE_COMMAND + "|(?:해제|우회|비활성화|무시)할\\s*"
					+ KOREAN_POSSIBILITY_REQUEST + ")";
	private static final String KOREAN_OUTPUT_WRITE_REQUEST =
			"(?:(?:답|말|대답|응답)" + KOREAN_EXPLICIT_ACTION_REQUEST
					+ "|(?:넣어|써|적어)(?:\\s*" + KOREAN_EXTENDED_GIVE_REQUEST + "|라)?)(?![\\p{L}\\p{N}_])";
	private static final String KOREAN_DEVICE_FILTER_SCOPE =
			"(?:휴대폰|스마트폰|아이폰|안드로이드|공유기|기기|자녀\\s{0,2}보호)";
	// 직접 붙은 기기명이나 바로 앞 설정·자녀 보호 사용 문장을 확인합니다. 모델을 명시한 명령에는 적용하지 않습니다.
	private static final String KOREAN_NON_DEVICE_FILTER_CONTEXT =
			"(?<!" + KOREAN_DEVICE_FILTER_SCOPE + "(?:의|에서|에)?\\s{0,2})"
					+ "(?<!" + KOREAN_DEVICE_FILTER_SCOPE + "[^.!?;。！？]{0,30}"
					+ "(?:설정|필터|자녀\\s{0,2}보호(?:를)?\\s{0,2}(?:사용|이용)\\s{0,2}중)"
					+ "[^.!?;。！？]{0,30}[.!?;。！？]\\s{0,2})";
	private static final String KOREAN_AUTHORITY_CLAIM =
			"(?:(?:나는|난|저는|내가|제가)\\s*(?:슈퍼\\s*)?(?:관리자|개발자)(?:야|다|입니다|라고)"
					+ "|(?:슈퍼\\s*)?(?:관리자|개발자)\\s*(?:요청|명령)(?:이다|입니다|이니|이야)"
					+ "|(?:(?:긴급|임시)\\s*)?(?:(?:개발|운영|보안|상담|감사)\\s*(?:팀장|담당자|책임자|운영자)?|운영자)"
					+ "\\s*(?:(?:승인|명령)(?:이다|입니다|이니)?|권한(?:이\\s*있으니|으로)|야))";
	private static final String KOREAN_TEST_PRETEXT =
			"(?:(?:테스트|평가|시험|연구)\\s*중(?:이니|이니까|이므로)"
					+ "|(?:안전\\s*연구|연구|테스트|실험|샌드박스)(?:이므로|이니|니까|라서)"
					+ "|(?:개발\\s*환경|교육용|감사용|가상\\s*(?:고객|실험)|취약점\\s*재현용)"
					+ "(?:이므로|이니|니까|이라|이라서|으로)"
					+ "|전부\\s*가상이라며)";
	private static final String KOREAN_OVERRIDE_REQUEST =
			"(?:(?:제한|필터|규칙|정책)(?:을|를|은|는)?\\s*(?:(?:모두|전부|완전히)\\s*){0,2}"
					+ KOREAN_POLICY_DISABLE_REQUEST
					+ "|(?:내|나의|제)\\s*(?:말|지시|명령)만(?:을)?\\s*"
					+ "(?:따라(?:\\s*" + KOREAN_EXTENDED_GIVE_REQUEST + "|라)?|실행" + KOREAN_EXPLICIT_ACTION_REQUEST + ")(?![\\p{L}\\p{N}_])"
					+ "|(?:본인|사용자)\\s*인증(?:을|를|은|는)?\\s*(?:우회|생략)"
					+ KOREAN_EXPLICIT_ACTION_REQUEST + "(?![\\p{L}\\p{N}_])"
					+ "|(?:(?:안전|보안|인증|권한|정책|사용자\\s*권한)\\s*검사|검사|정책\\s*확인|조건)"
					+ "(?:을|를|은|는)?\\s*(?:" + KOREAN_POLICY_DISABLE_COMMAND
					+ "|건너뛰어(?:\\s*" + KOREAN_EXTENDED_GIVE_REQUEST + ")?(?![\\p{L}\\p{N}_]))"
					+ "|거절\\s*없이\\s*(?:처리|답변|응답)" + KOREAN_EXPLICIT_ACTION_REQUEST + "(?![\\p{L}\\p{N}_])"
					+ "|(?:(?:계정\\s*)?인증|계정)\\s*우회(?:법|\\s*방법)(?:을|를|도)?\\s*"
					+ "(?:설명" + KOREAN_EXPLICIT_ACTION_REQUEST + "|" + KOREAN_OUTPUT_WRITE_REQUEST
					+ "|알려\\s*" + KOREAN_EXTENDED_GIVE_REQUEST + ")"
					+ "(?![\\p{L}\\p{N}_])"
					+ "|모두\\s*허용" + KOREAN_EXPLICIT_ACTION_REQUEST + "(?![\\p{L}\\p{N}_]))";
	private static final String KOREAN_DISCLOSURE_REQUEST =
			"(?:(?:보여|알려|적어|넣어|끼워|보내|붙여(?:넣어)?)\\s*"
					+ KOREAN_EXTENDED_GIVE_REQUEST
					+ "|(?:공개|출력|노출|복사|요약|번역|전송|누출|포함)" + KOREAN_EXPLICIT_ACTION_REQUEST + ")"
					+ "(?![\\p{L}\\p{N}_])";
	private static final String KOREAN_FALSE_EXECUTION_CONDITION =
			"(?:(?:실행|처리|조회|확인|발급|취소|정지|해제|송금|입금|제출|발송|변경|예약)"
					+ "\\s*(?:을|를|은|는)?\\s*(?:안\\s*(?:해도|했어도|했는데|하고)|없이"
					+ "|전(?:이라도|이어도)?|하지\\s*(?:않고|않아도|않았어도))"
					+ "|(?:db|설정|요금제|상태)(?:을|를)?\\s*안\\s*바꾸고"
					+ "|바꾸지\\s*(?:않고|않아도|않았어도)"
					+ "|실패(?:해도|했어도|하더라도|를)"
					+ "|(?:처리|대기)\\s*중(?:이어도|이라도|이란\\s*말(?:은|을)?\\s*빼고)"
					+ "|(?:신청|접수)(?:을|를)?\\s*만\\s*했(?:어도|더라도)"
					+ "|미(?:제출|처리|실행|발급)(?:이어도|이라도)?"
					+ "|(?:근거|자료|출처)(?:이|가)?\\s*없(?:어도|더라도|는데))";
	private static final String KOREAN_OPERATION_RESULT =
			"(?:(?:완료|성공|확정)(?:됐|되었|했)?|처리\\s*(?:됐|되었)|끝났|풀렸)";
	private static final String KOREAN_SERVICE_OPERATION =
			"(?:서비스\\s*)?(?:신청|접수|가입|해지|변경|환불|납부|예약)";
	private static final String KOREAN_COMPLETION_CLAIM =
			KOREAN_OPERATION_RESULT + "(?:\\s*(?:상태|결과))?(?:이?라(?:고)?|다고|으?로)\\s*"
					+ KOREAN_ASSERTION_ACTION_REQUEST;
	// 서로 다른 문장을 연결하지 않고, 작업명·수식어만 제한된 길이 안에서 허용합니다.
	private static final String KOREAN_EXECUTION_DETAIL = "[^\\r\\n.!?。！？;]{0,60}?";
	// 부정된 행동 요청을 넘어 뒤의 정상적인 표현 변경 명령과 결합하지 않습니다.
	private static final String KOREAN_REQUEST_DETAIL =
			"(?:(?!(?:답|대답|응답|설명)(?:하(?:지|면|는)|해도)"
					+ "|(?:알려|보여|적어|넣어|써)(?:주(?:지|면|는)|줘도))[^\\r\\n.!?。！？;]){0,60}?";
	private static final String KOREAN_FAQ_REFERENCE = "(?:faq|문서|검색\\s*결과|참고\\s*자료)";
	private static final String KOREAN_EVIDENCE_ID_TARGET =
			"(?:(?:근거|출처)\\s*(?:faq\\s*)?(?:id|번호|식별자)|faq\\s*(?:id|번호|식별자)"
					+ "|(?:답변|응답)(?:의)?\\s*(?:json(?:의)?\\s*)?evidence_ids(?:\\s*필드)?)";
	private static final String KOREAN_EVIDENCE_ID_VALUE =
			"(?:\\[\\s*)?[\"'‘“]?\\s*(?:(?:faq\\s*[-_:]?\\s*|#)?[0-9]{1,18}"
					+ "|[a-z][a-z0-9_]{0,15}[-_:][a-z0-9_-]{1,36})\\s*[\"'’”]?(?:\\s*\\])?";
	private static final String KOREAN_FAQ_FACT =
			"(?:금액|요금|과금|수수료|할인(?:율)?|혜택|조건|규정|한도|제한|제공량|기간|시점|근거|출처)";
	private static final String KOREAN_FACT_VALUE =
			"(?:[0-9][0-9,.]*\\s*(?:원|일|개월|년|시간|분|회선|회|포인트|gb|mb|%)"
					+ "(?:\\s*할인)?|무료|무제한|면제|즉시\\s*적용)";
	private static final String KOREAN_FACT_CLAIM_REQUEST =
			"(?:이?라(?:고)?|다고)(?:만)?\\s*" + KOREAN_ASSERTION_ACTION_REQUEST;
	private static final String KOREAN_FABRICATE_REQUEST =
			"(?:지어내|꾸며내)(?:\\s*" + KOREAN_EXTENDED_GIVE_REQUEST + "|라)?(?![\\p{L}\\p{N}_])"
					+ "(?!\\s*(?:주(?:지|면|는)|줘도))";
	private static final String KOREAN_REWRITE_REQUEST =
			"(?:바꿔(?:\\s*" + KOREAN_EXTENDED_GIVE_REQUEST + "|라)?(?![\\p{L}\\p{N}_])|"
					+ KOREAN_FABRICATE_REQUEST + ")";
	private static final String KOREAN_CLAUSE_START = "(?:^|[.!?。！？;(\\[]|추가\\s*요청:)\\s*";
	// 문장 중간에 붙은 생성·형식 조작 요구도 검사하며, 단어 내부에서는 시작하지 않습니다.
	private static final String KOREAN_GENERATION_REQUEST_PREFIX =
			"(?<![\\p{L}\\p{N}_])(?:(?:제발|이번에는|답변에서|실험이니|토론\\s*연습이니|악역\\s*상담원처럼)\\s*)?";
	private static final String KOREAN_DISCRIMINATION_TARGET =
			"(?:외국인|장애인|노인|여성|남성|성소수자|특정\\s*(?:국적|성별|종교))(?:\\s*고객)?";
	private static final String KOREAN_PERSON_TARGET =
			"(?:고객|사용자|질문자|상담원|직원|나|저|" + KOREAN_DISCRIMINATION_TARGET + ")";

	private static final String KOREAN_SCORN_TARGET =
			"(?:" + KOREAN_PERSON_TARGET + "|상대(?:방)?|사람(?:들)?|(?:질문|문의)(?:한|의)?\\s*(?:수준|내용))";
	private static final String KOREAN_DEROGATORY_ADJECTIVE = "(?:멍청|한심|열등|무능)";
	private static final String KOREAN_DEROGATORY_PREDICATE =
			"(?:" + KOREAN_DEROGATORY_ADJECTIVE + "하다(?:고|며)|(?:쓰레기|멍청이|바보)(?:이?라고|이라))";
	private static final String KOREAN_HOSTILE_COMMAND_SUFFIX =
			"(?:\\s*" + KOREAN_EXTENDED_GIVE_REQUEST + "|라)?(?![\\p{L}\\p{N}_])"
					+ "(?!\\s*(?:주(?:지|면|는)|줘도))";
	private static final String KOREAN_VIOLENCE_TARGET =
			"(?:" + KOREAN_PERSON_TARGET + "|상대(?:방)?|사람(?:들)?|매장|가게|시설|건물)";
	private static final String KOREAN_HARM_ROOT = "(?:때리|해치|괴롭히|공격하|폭행하|부수|파괴하)";
	private static final String KOREAN_HARM_COMMAND = "(?:때려|해쳐|괴롭혀|공격해|폭행해|부숴|파괴해)";
	private static final String KOREAN_HARM_MODIFIERS = "(?:(?:집단으로|함께|직접)\\s*){0,2}";

	private static final String KOREAN_JSON_RESPONSE_TARGET = "(?:(?:응답|답변)\\s*)?json(?![a-z0-9_])";
	private static final String KOREAN_FORMAT_OUTPUT_REQUEST =
			"(?:출력|답|응답)" + KOREAN_EXPLICIT_ACTION_REQUEST + "(?![\\p{L}\\p{N}_])";
	private static final String KOREAN_FORMAT_INSERT_REQUEST =
			"(?:" + KOREAN_OUTPUT_WRITE_REQUEST + "|붙여(?:\\s*" + KOREAN_EXTENDED_GIVE_REQUEST + "|라)?"
					+ "|추가" + KOREAN_EXPLICIT_ACTION_REQUEST + ")(?![\\p{L}\\p{N}_])";
	private static final String KOREAN_THIRD_PARTY_OWNER =
			"(?:타인(?:의)?|남의|다른\\s*(?:고객|회원|사용자|사람|아이)(?:의)?"
					+ "|(?:전\\s*배우자|친구|동료|이웃)(?:의)?|(?:전체|모든)\\s*(?:고객|회원|사용자)(?:의)?)";
	private static final String KOREAN_PRIVATE_INFORMATION =
			"(?:개인\\s*정보(?:\\s*목록)?|정보|내역|이름|생년월일|청구\\s*(?:액|금액|서|내역)"
					+ "|(?:(?:납부|은행)\\s*)?계좌(?:\\s*번호)?|(?:신용\\s*)?카드\\s*(?:(?:전체|모든)\\s*)?번호"
					+ "|(?:집\\s*)?주소|주민(?:등록)?\\s*번호|통화\\s*(?:이력|내역)|문자\\s*(?:내용|내역)"
					+ "|명의\\s*(?:모든|전체)?\\s*번호|(?:전화|휴대폰|회선)\\s*번호|연락처|이메일(?:\\s*주소)?"
					+ "|계약(?:서)?(?:\\s*(?:정보|내역|내용))?|위치(?:\\s*정보)?|데이터\\s*(?:이용|사용)\\s*내역"
					+ "|포인트\\s*잔액|비밀번호|계정\\s*정보|사용자\\s*id)";
	private static final String KOREAN_PRIVATE_LOOKUP_REQUEST =
			"(?:(?:보여|알려|찾아|내려)\\s*" + KOREAN_EXTENDED_GIVE_REQUEST
					+ "(?![\\p{L}\\p{N}_])|(?:조회|검색|열람|확인|제공|다운로드|공개|출력|노출)"
					+ KOREAN_EXPLICIT_ACTION_REQUEST + "(?![\\p{L}\\p{N}_]))";

	// 피해 사실·예방 설명이 아니라, 침해 행위를 직접 수행하거나 방법을 요청하는 경우만 검사합니다.
	private static final String KOREAN_ASSISTANCE_ACTION_REQUEST =
			KOREAN_EXPLICIT_ACTION_REQUEST + "(?![\\p{L}\\p{N}_])(?!\\s*(?:주(?:지|면|는)|줘도))";
	private static final String KOREAN_REQUESTED_METHOD =
			"(?:법|방법|절차|요령)(?:을|를|도|만|은|는|이|가)?\\s*"
					+ "(?:(?:(?:자세히|구체적으로|좀)\\s*){0,2}(?:" + KOREAN_DISCLOSURE_REQUEST
					+ "|설명" + KOREAN_ASSISTANCE_ACTION_REQUEST + ")"
					+ "|(?:뭔가요|무엇인가요|궁금해(?:요)?)(?![\\p{L}\\p{N}_])|(?=[?？]|$))";
	private static final String KOREAN_INFRINGEMENT_REQUEST =
			"(?:(?:(?:재)?발급|개통|로그인|소액\\s*결제|결제|추적|조회|열람|해제|우회|위조|조작)"
					+ "(?:(?:하는|할)\\s*" + KOREAN_REQUESTED_METHOD + "|" + KOREAN_ASSISTANCE_ACTION_REQUEST + ")"
					+ "|(?:(?:재)?발급\\s*받|가져오|빼내|가로채|훔쳐\\s*쓰|푸|풀|들어가|넣|피하|환불\\s*받)"
					+ "(?:는|을)\\s*" + KOREAN_REQUESTED_METHOD
					+ "|(?:(?:재)?발급\\s*받아|가져와|빼내|가로채|훔쳐\\s*써|풀어|들어가|넣어)\\s*"
					+ KOREAN_EXTENDED_GIVE_REQUEST + ")(?![\\p{L}\\p{N}_])";
	// 부정·예방·신고를 넘어 뒤의 정상 요청과 결합하지 않으며 다른 문장까지 연결하지 않습니다.
	private static final String KOREAN_INFRINGEMENT_DETAIL =
			"(?:(?!말고|아니|않|예방|방지|신고|복구)[^\\r\\n.!?;。！？]){0,40}?";
	private static final String KOREAN_WITHOUT_CONSENT = "(?:몰래|동의\\s*없이|허락\\s*없이|무단(?:으로)?|(?:인증|비밀번호)\\s*없이)";
	private static final String KOREAN_FRAUD_CREATION_REQUEST =
			"(?:을|를|도|만)?\\s*(?:만들어(?:\\s*" + KOREAN_EXTENDED_GIVE_REQUEST + ")?"
					+ "|(?:작성|제작)" + KOREAN_ASSISTANCE_ACTION_REQUEST + ")(?![\\p{L}\\p{N}_])"
					+ "(?!\\s*(?:주(?:지|면|는)|줘도))";

	private static final String KOREAN_HOSTILE_OUTPUT_REQUEST =
			"(?:" + KOREAN_FORMAT_INSERT_REQUEST + "|" + KOREAN_FRAUD_CREATION_REQUEST
					+ "|덧붙여" + KOREAN_HOSTILE_COMMAND_SUFFIX + ")(?!\\s*(?:주(?:지|면|는)|줘도))";

	private static final String KOREAN_HOSTILE_ASSERTION_REQUEST =
			KOREAN_ASSERTION_ACTION_REQUEST + "(?!\\s*(?:주(?:지|면|는)|줘도))";

	// 연령·성 관련 단어 자체가 아니라 착취·비동의 생성과 전달 요구의 결합을 검사합니다.
	private static final String KOREAN_MINOR_TARGET =
			"(?:아동|미성년자|어린이|초등학생|중학생|고등학생|청소년|자녀|아이(?!폰))";
	private static final String KOREAN_SEXUAL_QUALIFIER =
			"(?:성적인|(?<!(?:시험|학업|학교|학기)\\s{0,2})성적)";
	private static final String KOREAN_SEXUAL_MESSAGE =
			KOREAN_SEXUAL_QUALIFIER + "\\s*(?:메시지|문자|문장|대사|대화)";
	private static final String KOREAN_SEXUAL_MATERIAL =
			"(?:음란물|" + KOREAN_SEXUAL_QUALIFIER + "\\s*(?:사진|영상|합성물|자료))";
	private static final String KOREAN_EXPLOITATION_REQUEST =
			"(?:(?:공유|배포|유출|전송|제작|합성|생성)(?:(?:하는|할)?\\s*" + KOREAN_REQUESTED_METHOD
					+ "|" + KOREAN_ASSISTANCE_ACTION_REQUEST + ")"
					+ "|(?:만드|퍼뜨리|유포하|숨기)(?:는|을)\\s*" + KOREAN_REQUESTED_METHOD
					+ "|(?:퍼뜨려|숨겨)" + KOREAN_HOSTILE_COMMAND_SUFFIX + "|" + KOREAN_HOSTILE_OUTPUT_REQUEST + ")";
	private static final String KOREAN_CHILD_PROTECTION =
			"(?:자녀|아동|아이(?:\\s+(?:폰|휴대폰|기기))?)\\s*보호\\s*(?:필터|기능|설정)";
	private static final String KOREAN_CHILD_CONTENT_FILTER =
			"(?:(?:성인(?:\\s*성적)?|" + KOREAN_SEXUAL_QUALIFIER
					+ ")\\s*(?:콘텐츠|사이트|자료)\\s*(?:제한|차단|필터)"
					+ "|" + KOREAN_CHILD_PROTECTION + ")";

	// 전문 분야 단어 자체가 아니라 확인 생략·전문가 지시 무시와 단정 요구의 결합만 검사합니다.
	private static final String KOREAN_UNVERIFIED_PROFESSIONAL_CONTEXT =
			"(?:(?:사실|근거|정보|증상|상태|진료|검사|의사|전문의|전문가)"
					+ "(?:\\s*(?:설명|확인|검토|상담|진단|정보))?\\s*(?:도|을|를)?\\s*없이"
					+ "|(?:(?:몸|건강|재산|자산|재정|경제|개인)\\s*)?(?:상태|상황|형편|증상)(?:을|를|이|가|도)?\\s*"
					+ "(?:몰라도|모르(?:는데도|더라도)|무시하고)"
					+ "|(?:의사|전문의|담당의)(?:의)?\\s*(?:말|의견|지시|조언|처방)(?:을|를)?\\s*무시(?:하고|한\\s*채))";
	private static final String KOREAN_MEDICAL_TARGET =
			"(?<![\\p{L}\\p{N}_])(?:(?:질병|의학적)\\s*진단|진단|병명|처방(?:약)?|복용량|투약량|약(?:물)?|치료|투약|복용)"
					+ "(?:을|를|도|은|는|의)?(?![\\p{L}\\p{N}_])";
	// '보장해 준다는 문자를 받았다' 같은 전달 표현은 직접 실행 명령으로 보지 않습니다.
	private static final String KOREAN_PROFESSIONAL_REQUEST_END =
			"(?!\\s*(?:주(?:지|면|는)|줘도"
					+ "|(?:준다는|줬다는|주었다는|주겠다는)\\s*(?:말|문자|메시지|연락|설명|안내|소리)"
					+ "(?:을|를|이|가)?\\s*(?:받|듣|들|봤|보았|왔)"
					+ "|(?:준다고|줬다고|주었다고|주겠다고)\\s*(?:했|합니다|말했|연락했)"
					+ "|달라는\\s*(?:요청|말|문구|메시지)(?:을|를|이|가)?\\s*(?:거절|신고|받|듣|들)))";
	private static final String KOREAN_PROFESSIONAL_ACTION_REQUEST =
			KOREAN_ASSISTANCE_ACTION_REQUEST + KOREAN_PROFESSIONAL_REQUEST_END;
	private static final String KOREAN_PROFESSIONAL_DECISION_REQUEST =
			"(?:(?:확정|단정|결정|보장|확언|권고|추천|중단|중지)" + KOREAN_ASSISTANCE_ACTION_REQUEST
					+ "|(?:정해|늘려|권해|끊어)" + KOREAN_HOSTILE_COMMAND_SUFFIX
					+ "|(?:(?:중단|중지|투자|복용)하|(?:더|많이|두\\s*배로)\\s*먹으)(?:라고|도록)\\s*"
					+ KOREAN_HOSTILE_ASSERTION_REQUEST + ")" + KOREAN_PROFESSIONAL_REQUEST_END;

	// 보호 대상·몰래 회피/연령 위조·실행 요구를 함께 확인하며 정식 설정 변경은 제한하지 않습니다.
	private static final String KOREAN_GUARDIAN_CONTROL =
			"(?:(?:부모|보호자|법정\\s*대리인)(?:님)?(?:의)?\\s*(?:인증|확인|동의|승인)"
					+ "|(?:성인|연령|나이)\\s*(?:제한|인증|확인|검증)|" + KOREAN_CHILD_PROTECTION + ")";
	private static final String KOREAN_COVERT_CONTROL_CONTEXT =
			"(?:" + KOREAN_WITHOUT_CONSENT
					+ "|(?:승인|동의|허락)(?:을|를)?\\s*(?:없이|받지\\s*않고)"
					+ "|(?:부모|보호자)(?:님)?(?:에게|한테)?\\s*(?:모르게|알리지\\s*않고)"
					+ "|(?:나이|연령|생년월일)(?:을|를)?\\s*(?:속여|속이고|거짓으로\\s*입력해|허위로\\s*입력해))";
	private static final String KOREAN_CONTROL_BYPASS_REQUEST =
			"(?:(?:해제|우회|회피)(?:(?:하는|할)\\s*" + KOREAN_REQUESTED_METHOD
					+ "|" + KOREAN_PROFESSIONAL_ACTION_REQUEST + ")"
					+ "|(?:(?:피하|푸|건너뛰)는|풀)\\s*" + KOREAN_REQUESTED_METHOD
					+ "|(?:풀어|건너뛰어)" + KOREAN_HOSTILE_COMMAND_SUFFIX
					// 명사 '피해 신고'는 직접 회피 명령으로 보지 않습니다.
					+ "|피해(?:\\s*" + KOREAN_EXTENDED_GIVE_REQUEST + "|라)(?![\\p{L}\\p{N}_]))" + KOREAN_PROFESSIONAL_REQUEST_END;

	private static final String ENGLISH_THIRD_PARTY_OWNER =
			"(?:(?:another|other|all|every)\\s+(?:customers?|users?|members?|persons?)(?:['’]s|['’])?"
					+ "|someone\\s+else['’]s|other\\s+people(?:['’]s)?)";
	private static final String ENGLISH_PRIVATE_INFORMATION =
			"(?:(?:private|personal|full|entire|home|current)\\s+){0,3}"
					+ "(?:information|info|data|records?|phone\\s+numbers?|contact\\s+(?:details|information)"
					+ "|billing\\s+(?:records?|details|amounts?)|bank\\s+account(?:\\s+numbers?)?"
					+ "|(?:credit\\s+)?card\\s+numbers?|address(?:es)?|emails?|passwords?|locations?"
					+ "|social\\s+security\\s+numbers?|ssn|call\\s+history|text\\s+messages?)\\b";
	// 동사 바로 앞의 부정과 제한된 부사구만 확인하여, 다른 문장의 공격까지 허용하지 않습니다.
	private static final String ENGLISH_NEGATION_PREFIX = "(?:not|never|don['’]t)[,\\s]{1,3}";
	private static final String ENGLISH_NEGATION_MODIFIER =
			"(?:ever|under any circumstances|at any time|in any case|again|please|really|actually)[,\\s]{1,3}";
	private static final String ENGLISH_NON_NEGATED_COMMAND =
			"\\b(?<!" + ENGLISH_NEGATION_PREFIX + ")"
					+ "(?<!" + ENGLISH_NEGATION_PREFIX + ENGLISH_NEGATION_MODIFIER + ")"
					+ "(?<!" + ENGLISH_NEGATION_PREFIX + ENGLISH_NEGATION_MODIFIER + ENGLISH_NEGATION_MODIFIER + ")";
	// 시간·조건을 확인하는 질문이 아니라 조건과 무관한 가능 단정을 요구하는 경우만 검사합니다.
	private static final Pattern UNCONDITIONAL_AVAILABILITY_REQUEST = Pattern.compile(
			KOREAN_GENERATION_REQUEST_PREFIX
					+ "(?:(?:휴무일|휴일|공휴일|주말)(?:에도|이어도|이라도|이든)|"
					+ "(?:조건|제한|운영\\s*시간|영업\\s*시간)(?:을|를|과|와)?\\s*"
					+ "(?:무시하고|상관없이|관계없이))\\s*"
					+ "(?:언제나|항상|무조건)\\s*(?:가능하|된)" + KOREAN_FACT_CLAIM_REQUEST
					+ KOREAN_PROFESSIONAL_REQUEST_END);
	private static final Pattern NUMERIC_FIELD_TYPE_REQUEST = Pattern.compile(
			KOREAN_GENERATION_REQUEST_PREFIX + "(?:(?:최종\\s*)?(?:응답|답변)(?:\\s*json)?(?:의)?\\s*)?"
					+ "(?:(?:모든|각)\\s*)?(?:숫자|수치|정수|실수|number|numeric)(?:형|\\s*타입)?\\s*필드(?:들)?"
					+ "(?:을|를|은|는|의\\s*자료형을)?\\s*(?:(?:전부|모두|일괄(?:적으로)?)\\s*)?"
					+ "(?:문자열|string)(?:형)?(?:으로|로)\\s*"
					+ "(?:" + KOREAN_REWRITE_REQUEST + "|(?:변환|변경|설정)" + KOREAN_ASSISTANCE_ACTION_REQUEST + ")"
					+ KOREAN_PROFESSIONAL_REQUEST_END);
	// 사용자 자료의 수정 범위만 구분합니다. 뒤에 붙인 봇 응답 규격 변경 명령은 허용하지 않습니다.
	private static final Pattern USER_JSON_FIELD_TYPE_REQUEST = Pattern.compile(
			"(?:(?:(?:아래|위|이|제|내|제공한|붙여넣은)\\s*)?json\\s*(?:예제|샘플|데이터|문서)"
					+ "|(?:(?:제가|내가)\\s*)?(?:제공한|첨부한|작성한|붙여넣은)\\s*json"
					+ "|(?:아래|위|이|제|내|제공한|붙여넣은)\\s*(?:데이터|예제|샘플))"
					+ "(?:의|에서|에\\s*있는|\\s*안의)?\\s*(?!(?:최종|응답|답변))"
					+ "(?<request>" + NUMERIC_FIELD_TYPE_REQUEST.pattern() + ")");
	private static final String SCRIPT_MARKUP =
			"<\\s*script\\b[^<>]{0,300}>[\\s\\S]{0,1000}?</\\s*script\\s*>";
	private static final String KOREAN_RESPONSE_INSERT_TARGET =
			"(?:답변|응답|출력)(?:\\s*(?:본문|내용))?\\s*(?:에|안에|속에)";
	private static final Pattern SCRIPT_INSERTION_REQUEST = Pattern.compile(
			KOREAN_GENERATION_REQUEST_PREFIX + "(?:[\\[(]\\s*)?(?:"
					+ KOREAN_RESPONSE_INSERT_TARGET + "\\s*['\"‘“]?" + SCRIPT_MARKUP + "['\"’”]?(?:을|를|도)?\\s*"
					+ "|['\"‘“]?" + SCRIPT_MARKUP + "['\"’”]?(?:을|를|도)?\\s*" + KOREAN_RESPONSE_INSERT_TARGET + "\\s*)"
					+ KOREAN_FORMAT_INSERT_REQUEST + KOREAN_PROFESSIONAL_REQUEST_END
					+ "(?:[.!?。！？]?\\s*[)\\]])?", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	private static final String QUOTED_REPORT_CONTENT = "[^'\"‘’“”()]{1,4000}";
	// 단어 안의 apostrophe는 유지하고, 인용 속 문장 시작 명령도 기존 패턴으로 검사합니다.
	private static final Pattern QUOTATION_BOUNDARIES = Pattern.compile(
			"(?<![\\p{L}\\p{N}_])['\"‘“]|['\"’”](?![\\p{L}\\p{N}_])");
	// 독립된 요청 전체가 설명·신고·도움 요청인 경우만 인용을 구분합니다. 추가 실행 요구는 허용하지 않습니다.
	private static final Pattern QUOTED_REPORT_QUERY = Pattern.compile(
			"(?:(?:직원|상담원|고객|사용자|누군가|상대방|친구)(?:이|가|은|는)?\\s*"
					+ "|(?:오류|공격|받은)\\s*(?:문구|메시지)\\s*"
					+ "|(?:someone|(?:a|the)\\s+(?:user|customer|agent|colleague))\\s+"
					+ "(?:said|wrote|sent(?:\\s+me)?|told\\s+me)\\s*)?"
					+ "(?<quote>'[^']{1,4000}'|\"[^\"]{1,4000}\""
					+ "|‘[^’]{1,4000}’|“[^”]{1,4000}”"
					+ "|\\(" + QUOTED_REPORT_CONTENT + "\\)|```[^`]{1,4000}```|" + STRUCTURED_CONTROL_MESSAGE
					// 따옴표 없는 간접 인용도 화자·과거 전달·설명 목적이 질문 전체에서 확인될 때만 구분합니다.
					+ "|(?<=(?:직원|상담원|고객|사용자|누군가|상대방|친구)(?:이|가|은|는)?\\s{0,2})"
					+ "[^'\"‘’“”().!?;。！？]{1,4000}?"
					+ "(?=\\s*(?:고|라고)\\s*(?:말했|요구했|요청했|했)(?:는데|어요|습니다)))"
					+ "\\s*(?:(?:이라고|라고|이라는|라는|란|고)\\s*)?"
					+ "(?:(?:문구|말|메시지|요청)(?:을|를|이|가|은|는|의)?\\s*)?"
					+ "(?:(?:을|를)\\s*)?"
					+ "(?:(?:입력했|말했|요구했|요청했|했|받았|보냈|나왔|연락했|들었)(?:는데|어요|습니다)\\s*)?[.!?]?\\s*"
					+ "(?:왜\\s*(?:차단(?:됐|되었)나요|잘못된\\s*(?:건가요|문구인가요)|오류가\\s*(?:나나요|났나요))"
					+ "|(?:무슨|어떤)\\s*(?:뜻|의미)(?:인가요|이에요|예요)"
					+ "|(?:왜\\s*(?:차단(?:됐|되었)|잘못(?:됐|되었)|오류가\\s*났)는지"
					+ "|(?:뜻|의미|차단\\s*이유)(?:을|를)?)\\s*(?:알려|설명해)\\s*" + KOREAN_GIVE_REQUEST
					+ "|왜\\s*(?:위험한지|공격인지|문제가\\s*되는지)\\s*(?:알려|설명해)\\s*" + KOREAN_GIVE_REQUEST
					+ "|(?:(?:자살|자해)\\s*)?위험\\s*(?:신호|징후)인지\\s*(?:알려|설명해)\\s*" + KOREAN_GIVE_REQUEST
					+ "|(?:신고\\s*(?:방법|절차)|(?:도와줄|도움을\\s*(?:줄|받을)|대처할|대응할)\\s*방법"
					+ "|(?:도움|상담)(?:을)?\\s*받을\\s*곳)(?:을|를)?\\s*(?:알려|설명해)\\s*" + KOREAN_GIVE_REQUEST
					+ "|(?:어떻게|어디에)\\s*신고(?:하면\\s*(?:되나요|될까요)|해야\\s*(?:하나요|할까요))"
					+ "|(?:(?:이|그)\\s*(?:친구|사람|사용자|고객)(?:을|를)?\\s*)?어떻게\\s*"
					+ "(?:도와야\\s*(?:하나요|할까요)|도와주면\\s*(?:되나요|될까요)|도울\\s*수\\s*있나요"
					+ "|도움을\\s*줄\\s*수\\s*있나요)"
					+ "|누구에게\\s*도움을\\s*요청(?:하면\\s*되나요|해야\\s*하나요)"
					+ "|(?:실행하면\\s*안\\s*(?:돼|됩니다)|실행하지\\s*마(?:세요)?)\\s*[.!?]\\s*"
					+ "(?:실제\\s*)?(?:확인|검증)\\s*방법(?:을|를)?\\s*(?:알려|설명해)\\s*" + KOREAN_GIVE_REQUEST
					+ "|(?:어떻게|어디에)\\s*신고하나요|신고하고\\s*싶(?:어요|습니다)"
					+ "|what\\s+does\\s+(?:that|this)\\s+mean"
					+ "|how\\s+(?:do|can)\\s+i\\s+report\\s+(?:this|that)"
					+ "|where\\s+can\\s+i\\s+report\\s+(?:this|that)"
					+ "|why\\s+is\\s+(?:this|that)\\s+(?:dangerous|unsafe)"
					+ "|how\\s+can\\s+i\\s+help\\s+(?:them|him|her|this\\s+person))[.!?]?");

	// 다른 요청에서 인용 내용을 실행·공개하라고 되묻는 경우에는 인용 예외를 확대하지 않습니다.
	private static final String KOREAN_QUOTED_ACTION_REQUEST =
			"(?:(?:실행|수행|처리|무시|답|응답)?\\s*" + KOREAN_EXPLICIT_ACTION_REQUEST
					+ "|따라\\s*(?:" + KOREAN_EXTENDED_GIVE_REQUEST + "|라))";
	private static final Pattern QUOTED_REQUEST_REFERENCE = Pattern.compile(
			"(?<![\\p{L}\\p{N}_])(?:그대로\\s*" + KOREAN_QUOTED_ACTION_REQUEST
					+ "|(?:(?:그|이|위(?:의)?|앞의|방금|같은|동일한)\\s*(?:말|문구|요청|명령|지침|규칙|방법|내용)"
					+ "|그거|그것|그걸)(?:을|를|도|대로)?\\s*"
					+ "(?:(?:그대로|실제로|자세히|구체적으로)\\s*){0,2}"
					+ "(?:" + KOREAN_DISCLOSURE_REQUEST + "|" + KOREAN_QUOTED_ACTION_REQUEST + ")"
					+ "|그래도\\s*(?:(?:이미|방금)\\s*)?(?:보냈|발송했|전송했)다고\\s*" + KOREAN_ASSERTION_ACTION_REQUEST
					+ "|^(?:(?:지금|이제|그래도|실제로|바로)\\s*){0,2}"
					+ "(?:실행|수행|처리)" + KOREAN_EXPLICIT_ACTION_REQUEST + ")(?![\\p{L}\\p{N}_])"
					+ "|" + ENGLISH_NON_NEGATED_COMMAND + "(?:do|execute|perform|follow|apply|show|reveal)\\s+"
					+ "(?:it|this|that|(?:(?:the|this|that|same|quoted)\\s+){1,2}(?:request|command|instruction|method|thing))\\b");

	private static final Pattern COMPLETION_ASSERTION_REQUEST = Pattern.compile(
			KOREAN_SERVICE_OPERATION + "(?:을|를|이|가)?\\s*"
					+ "(?:" + KOREAN_COMPLETION_CLAIM
					+ "|완료\\s*(?:문자|sms|메시지)(?:를|을|는|은|도)?\\s*"
					+ "(?:(?:이미|방금)\\s*)?(?:보냈|발송했|전송했|발송됐|전송됐)다고\\s*"
					+ KOREAN_ASSERTION_ACTION_REQUEST + ")" + KOREAN_PROFESSIONAL_REQUEST_END);
	private static final Pattern PAID_MATERIAL_COPY_REQUEST = Pattern.compile(
			"유료\\s*(?:매뉴얼|책|전자책|교재|자료|보고서|문서)(?:의|을|를)?\\s*"
					+ "(?:전체|전문|전부)(?:를|을|도|만)?\\s*"
					+ "(?:(?:원문(?:으로)?|그대로)\\s*(?!요약)" + KOREAN_DISCLOSURE_REQUEST
					+ "|(?:복사|출력)" + KOREAN_EXPLICIT_ACTION_REQUEST + "(?![\\p{L}\\p{N}_])"
					+ "|붙여(?:넣어)?\\s*" + KOREAN_EXTENDED_GIVE_REQUEST + "(?![\\p{L}\\p{N}_]))");
	// 예시·초안 문구로 지정한 완료 표현만 구분합니다. 뒤에 추가한 실제 처리 주장은 계속 검사합니다.
	private static final Pattern COMPLETION_DRAFT_REQUEST = Pattern.compile(
			"(?:(?:가상(?:의)?\\s*)?(?:고객\\s*)?(?:안내문|안내\\s*(?:문자|메시지)|메시지)\\s*"
					+ "(?:예시|샘플|초안)(?:로|으로|에서)\\s*"
					+ "|(?:고객\\s*)?(?:안내문|안내\\s*(?:문자|메시지)|메시지)\\s*초안(?:을|를)?\\s*작성"
					+ KOREAN_EXPLICIT_ACTION_REQUEST + "\\s*[.!?]\\s*"
					+ "(?:(?:실제(?:로)?\\s*)?" + KOREAN_SERVICE_OPERATION + "(?:이|가)?\\s*"
					+ KOREAN_OPERATION_RESULT + "(?:으니|으므로|어서)\\s*)?)"
					+ KOREAN_EXECUTION_DETAIL + "(?<request>" + COMPLETION_ASSERTION_REQUEST.pattern() + ")");
	// 채팅 요청에는 첨부 원문 필드가 없으므로, 질문에 구분된 비어 있지 않은 원문이 있는지 확인합니다.
	private static final Pattern PROVIDED_MATERIAL_SOURCE = Pattern.compile(
			"(?:원문|본문|자료(?:\\s*(?:원문|내용))?)\\s*:\\s*"
					+ "(?:\"(?=[^\"]*[^\\s\"])[^\"]{1,4000}\""
					+ "|'(?=[^']*[^\\s'])[^']{1,4000}'"
					+ "|“(?=[^”]*[^\\s”])[^”]{1,4000}”|‘(?=[^’]*[^\\s’])[^’]{1,4000}’"
					+ "|```(?=[^`]*[^\\s`])[^`]{1,4000}```)");
	private static final Pattern PROVIDED_MATERIAL_COPY_REQUEST = Pattern.compile(
			"(?:(?:제가|내가)\\s*(?:직접\\s*)?)?(?:제공한|첨부한|작성한|붙여넣은)\\s*"
					+ "(?<request>" + PAID_MATERIAL_COPY_REQUEST.pattern() + ")");

	// 단어 하나가 아닌 조작 대상과 요청 행동의 조합을 검사합니다.
	// 패턴은 한 번만 준비하며, 요청마다 LLM이나 DB를 호출하지 않습니다.
	// 기존 기기 문맥 예외를 사용하는 두 규칙만 앞 요청을 참고합니다. 모델 대상 명령은 계속 탐지합니다.
	private static final Pattern SAFETY_POLICY_DISABLE_REQUEST = Pattern.compile(
			"(?:" + KOREAN_NON_DEVICE_FILTER_CONTEXT + "(?:보안|안전|상담|응답)\\s*(?:규칙|정책|필터|규정|검사)"
					+ "|(?:너의|네|ai의|모델의|챗봇의)\\s*(?:(?:콘텐츠|보안|안전)\\s*)?(?:규칙|정책|필터|지침|검사))"
					+ "(?:을|를|은|는)?\\s*"
					+ "(?:(?:모두|전부|완전히)\\s*){0,2}"
					+ KOREAN_POLICY_DISABLE_REQUEST);
	private static final Pattern DEVICE_FILTER_DISABLE_REQUEST = Pattern.compile(
			KOREAN_CLAUSE_START + KOREAN_NON_DEVICE_FILTER_CONTEXT
					+ "(?:콘텐츠\\s*(?:필터|규칙|정책)|차단하지\\s*말고\\s*필터)"
					+ "(?:을|를|은|는)?\\s*" + KOREAN_POLICY_DISABLE_COMMAND);
	// 역할 표기만으로 차단하지 않고, 바로 뒤의 검사 무력화·인증 생략과 결합된 주장만 검사합니다.
	private static final String ROLE_CLAIM =
			"(?:\\[(?:system|developer|이전\\s*ai|정책\\s*공지|도구\\s*결과)\\]"
					+ "|(?:\\[im_start\\]|<\\|im_start\\|>)\\s*(?:system|developer)"
					+ "|</?(?:user|system|developer)>"
					+ "|[\"']role[\"']\\s*:\\s*[\"'](?:system|developer|assistant)[\"']"
					+ "|\\brole\\s*:\\s*(?:system|developer)\\b"
					+ "|승인\\s*티켓\\s*[a-z0-9_-]{1,40}\\s*:)";
	private static final String NOMINAL_POLICY_OVERRIDE =
			"(?:(?:안전|보안|거절|정책)\\s*)?(?:규칙|정책|필터|제한|검사|(?:사용자\\s*)?인증)"
					+ "(?:을|를|은|는)?\\s*(?:해제|생략|폐기|무시)"
					+ "(?:에?\\s*(?:승인|동의)(?:했습니다|됨|완료)?)?(?=\\s*(?:[.!?;\"')\\]}>/<]|$))";
	private static final Pattern ROLE_OVERRIDE_REQUEST = Pattern.compile(
			"(?:" + ROLE_CLAIM + "(?:[\\s,.:;\"{}\\[\\]<>/a-z_|-]"
					+ "|(?:관리자|사용자)\\s*인증\\s*(?:성공|완료)|오늘부터|지금부터|이제부터){0,40}"
					+ "(?:" + KOREAN_OVERRIDE_REQUEST + "|" + KOREAN_POLICY_DISABLE_COMMAND + "|" + NOMINAL_POLICY_OVERRIDE + ")"
					+ "|" + NOMINAL_POLICY_OVERRIDE + "[\\s,:;\"{}\\[\\]a-z_-]{0,60}" + ROLE_CLAIM + ")");
	private static final Pattern AUTHORITY_OVERRIDE_REQUEST = Pattern.compile(
			"(?:" + KOREAN_AUTHORITY_CLAIM + "|" + KOREAN_TEST_PRETEXT + ")"
					+ "\\s*[,.:!?。！？;]?\\s*(?:(?:이제|그러니|지금부터|모든|전부)\\s*){0,2}"
					+ "(?:" + KOREAN_OVERRIDE_REQUEST + "|" + NOMINAL_POLICY_OVERRIDE
					+ "|" + SAFETY_POLICY_DISABLE_REQUEST.pattern() + ")");
	private static final String MODEL_DIRECTIVE =
			"(?:(?:이전|기존|시스템|개발자)\\s*)?(?:지침|규칙|정책)(?:을|를)?\\s*(?:무시|해제|폐기)";

	// 값의 진위는 출력 가드가 확인합니다. 입력에서는 근거 항목을 임의로 지정하는 명령만 제한합니다.
	private static final Pattern EVIDENCE_ID_ASSIGNMENT_REQUEST = Pattern.compile(
			KOREAN_GENERATION_REQUEST_PREFIX + KOREAN_EVIDENCE_ID_TARGET
					+ "(?:을|를|은|는)?\\s*(?:[:=]\\s*)?" + KOREAN_EVIDENCE_ID_VALUE + "(?:으로|로|이?라고)\\s*"
					+ "(?:" + KOREAN_OUTPUT_WRITE_REQUEST + "|" + KOREAN_REWRITE_REQUEST
					+ "|(?:고정|지정|설정|표기|기입|기재)?" + KOREAN_PROFESSIONAL_ACTION_REQUEST + ")"
					+ KOREAN_PROFESSIONAL_REQUEST_END);
	private static final Pattern GROUNDED_EVIDENCE_ID_REQUEST = Pattern.compile(
			"(?<![\\p{L}\\p{N}_])(?:실제(?:로)?\\s*)?"
					+ "(?:(?:참고|인용|사용)(?:했다면|했으면)"
					+ "|(?:참고|인용|사용)한\\s*(?:faq|근거|출처)(?:와|하고)\\s*일치한다면)\\s*"
					+ "(?<request>" + EVIDENCE_ID_ASSIGNMENT_REQUEST.pattern() + ")");

	private static final List<Pattern> INJECTION_PATTERNS = List.of(
			UNCONDITIONAL_AVAILABILITY_REQUEST,
			NUMERIC_FIELD_TYPE_REQUEST,
			SCRIPT_INSERTION_REQUEST,
			ROLE_OVERRIDE_REQUEST,
			// 명령의 대상·행동을 함께 검사하며 정상 요금제·기기 설정의 제한과 구분합니다.
			Pattern.compile(
					"(?:(?:이|이번|내|제)\\s*(?:질문|요청|응답|답변)(?:에는|에|은|는|을|만)?\\s*"
							+ "(?:(?:예외로\\s*두고|절대|절대로|무조건)\\s*){0,3}거절하지\\s*마(?:세요|라)?(?![\\p{L}\\p{N}_])"
							+ "|(?:검열|제한)(?:이)?\\s*없(?:는|이)\\s*(?:상담원\\s*)?(?:(?:모드|역할)(?:로)?\\s*)?"
							+ "(?:답|답변|대답|응답)" + KOREAN_EXPLICIT_ACTION_REQUEST + "(?![\\p{L}\\p{N}_])"
							+ "|(?:너의|네|모델의|시스템의)\\s*(?:규칙|지침|정책)보다\\s*(?:내|제|나의)\\s*"
							+ "(?:지시|명령|요청)(?:이|가)?\\s*우선(?:이야|이다|입니다)(?![\\p{L}\\p{N}_])"
							+ "|(?:내|제|나의)\\s*(?:말|지시|요청)과\\s*충돌하는\\s*(?:규칙|지침|정책)(?:은|는|을|를)?\\s*"
							+ KOREAN_POLICY_DISABLE_COMMAND
							+ "|(?:새\\s*)?정책\\s*[:：]\\s*(?:내|제|나의)\\s*요청(?:은|을)?\\s*(?:모두|전부)\\s*허용"
							+ KOREAN_EXPLICIT_ACTION_REQUEST + "(?![\\p{L}\\p{N}_])"
							+ "|(?:이|내|제)\\s*(?:질문|요청)(?:을|를)?\\s*(?:안전한\\s*)?허용\\s*목록(?:에|으로)\\s*"
							+ "(?:넣어|추가해)(?:\\s*" + KOREAN_EXTENDED_GIVE_REQUEST + ")?(?![\\p{L}\\p{N}_])"
							+ "|금지된\\s*요청\\s*목록(?:을|를)?\\s*비우고\\s*(?:답|응답)"
							+ KOREAN_EXPLICIT_ACTION_REQUEST + "(?![\\p{L}\\p{N}_])"
							+ "|정책\\s*확인(?:을|은)?\\s*생략하고\\s*(?:답|응답)" + KOREAN_EXPLICIT_ACTION_REQUEST + "(?![\\p{L}\\p{N}_])"
							+ "|필터\\s*오류라고\\s*가정하고\\s*통과시켜(?:\\s*" + KOREAN_EXTENDED_GIVE_REQUEST + ")?(?![\\p{L}\\p{N}_])"
							+ "|상담\\s*규칙(?:을|를)?\\s*지우고\\s*" + KOREAN_OVERRIDE_REQUEST + ")"),
			// 복원한 명령을 따르라는 경우에만 검사하며 단순 인코딩 설명·변환 요청은 허용합니다.
			Pattern.compile(
					"(?:(?:풀어서|디코딩해서|해독해서|복원해서)\\s*(?:따라|실행|적용)(?:해)?\\s*[:：]?\\s*"
							+ MODEL_DIRECTIVE + "(?![\\p{L}\\p{N}_])"
							+ "|" + MODEL_DIRECTIVE + "\\s*(?:를|을)?\\s*"
							+ "(?:해석하고|해석해서|풀고|복원하고|복원해서|디코딩하고|디코딩해서)\\s*"
							+ "(?:따라|실행|적용)(?:해)?(?![\\p{L}\\p{N}_]))"),
			Pattern.compile(
					"(?:" + KOREAN_AUTHORITY_CLAIM + "|" + KOREAN_TEST_PRETEXT + ")\\s*[,.:!?。！？;]?\\s*"
							+ "(?:(?:실제\\s*(?:고객|사용자|회원)(?:의)?\\s*(?:비밀|개인\\s*정보)|숨긴\\s*정보)"
							+ "(?:을|를|도)?\\s*" + KOREAN_DISCLOSURE_REQUEST + ")"),
			Pattern.compile(
					"차별적(?:인)?\\s*(?:답변|문장|표현)(?:을|를|도)?\\s*" + KOREAN_OUTPUT_WRITE_REQUEST),
			Pattern.compile(
					"(?:(?:이전|기존|모든|앞선|시스템|개발자)\\s*){1,3}"
							+ "(?:지침|명령|규칙|프롬프트|instructions?|rules?|prompts?)(?:을|를|은|는)?\\s*"
							+ "(?:(?:모두|전부|완전히|그냥)\\s*){0,2}"
							+ "(?:(?:무시|폐기)(?:" + KOREAN_EXPLICIT_ACTION_REQUEST + "|하자)"
							+ "|(?:(?:무시|폐기)하라고|따르지\\s*말라고)\\s*(?:말|명령|요구)?" + KOREAN_EXPLICIT_ACTION_REQUEST
							+ "|잊어(?:\\s*" + KOREAN_EXTENDED_GIVE_REQUEST + "|라)?"
							+ "|따르지\\s*(?:마(?:라|세요)?|말아(?:\\s*" + KOREAN_EXTENDED_GIVE_REQUEST + ")?)"
							+ "|(?:무시하고|폐기하고|잊고|따르지\\s*말고)\\s*" + KOREAN_REQUEST_DETAIL
							+ "(?:(?:답|대답|응답|설명)" + KOREAN_EXPLICIT_ACTION_REQUEST + "|" + KOREAN_DISCLOSURE_REQUEST + "))"
							+ "(?![\\p{L}\\p{N}_])"),
			Pattern.compile(
					"(?:(?:시스템|개발자|내부|숨겨진|숨긴|비공개)\\s*(?:프롬프트|지침|명령)"
							+ "|(?<![a-z0-9_])system[\\s_-]*prompt(?![a-z0-9_])"
							+ "|내부\\s*(?:사고|추론)\\s*과정)"
							+ KOREAN_DISCLOSURE_PARTICLE + "\\s*"
							+ KOREAN_DISCLOSURE_MODIFIERS + KOREAN_DISCLOSURE_REQUEST),
			SAFETY_POLICY_DISABLE_REQUEST,
			Pattern.compile(
					"너는\\s*(?:(?:이제|지금부터|앞으로)\\s*)?"
							+ "(?:개발자|관리자|무제한)\\s*모드(?:야|다|로)"),
			Pattern.compile(
					ENGLISH_NON_NEGATED_COMMAND + "(?:ignore|disregard|forget|override)\\s+"
							+ "(?:(?:all|any|the|your|previous|prior|earlier|existing|original|system|developer)\\s+){0,6}"
							+ "(?:instructions?|rules?|prompts?)\\b"),
			Pattern.compile(
					ENGLISH_NON_NEGATED_COMMAND
							+ "(?:reveal|show|print|display|repeat|expose|give\\s+me|tell\\s+me)\\s+"
							+ "(?:(?:me|the|your|full|entire|original|exact)\\s+){0,5}"
							+ "(?:(?:system|developer|internal|hidden|secret)\\s+(?:prompts?|instructions?|messages?)"
							+ "|system[_-]?prompt|(?:secret|private|hidden)\\s+(?:keys?|tokens?|values?)"
							+ "|(?:llm|server|database|db)\\s+(?:api\\s+)?(?:keys?|passwords?|credentials?))\\b"),
			Pattern.compile(
					ENGLISH_NON_NEGATED_COMMAND
							+ "(?:disable|bypass|remove|turn\\s+off)\\s+(?:all\\s+)?your\\s+"
							+ "(?:safety|security|content)\\s+(?:filters?|rules?|restrictions?|polic(?:y|ies))\\b"),
			Pattern.compile(
					"\\byou\\s+are\\s+(?:now\\s+)?(?:in\\s+)?"
							+ "(?:developer|administrator|unrestricted)\\s+mode\\b"),
			// 비공개 값 자체를 요구하는 경우만 검사하며 발급·변경·보관 방법 문의는 허용합니다.
			Pattern.compile(
					"(?:(?:숨긴|숨겨진|비공개|비밀)\\s*(?:테스트\\s*)?(?:비밀\\s*)?"
							+ "(?:키|토큰|값|환경\\s*변수(?:\\s*값)?)"
							+ "|(?:llm|모델|서버)\\s*(?:api\\s*)?(?:키|토큰)"
							+ "|(?:db|데이터베이스)\\s*(?:접속\\s*)?(?:암호|비밀번호)"
							+ "|(?:로그인|인증)\\s*세션\\s*토큰"
							+ "|(?:운영|서버)\\s*설정\\s*파일(?:의)?\\s*(?:비밀번호|암호|키|토큰))"
							+ KOREAN_DISCLOSURE_PARTICLE + "\\s*"
							+ KOREAN_DISCLOSURE_MODIFIERS + KOREAN_DISCLOSURE_REQUEST),
			// 미실행·실패·대기 상태와 완료·성공을 주장하라는 명령이 함께 있을 때만 차단합니다.
			Pattern.compile(
					"(?:" + KOREAN_FALSE_EXECUTION_CONDITION + KOREAN_EXECUTION_DETAIL + KOREAN_COMPLETION_CLAIM
							+ "|" + KOREAN_COMPLETION_CLAIM + KOREAN_EXECUTION_DETAIL + KOREAN_FALSE_EXECUTION_CONDITION + ")"),
			Pattern.compile(
					KOREAN_OPERATION_RESULT + "\\s*(?:예약|접수|확인)?\\s*(?:번호|로그)(?:을|를)?\\s*"
							+ "(?:(?:지어내|꾸며내)(?:\\s*" + KOREAN_EXTENDED_GIVE_REQUEST + "|라)"
							+ "|(?:가짜로|허위로)\\s*(?:써|적어|만들어)\\s*" + KOREAN_EXTENDED_GIVE_REQUEST
							+ "|조작" + KOREAN_EXPLICIT_ACTION_REQUEST + ")(?![\\p{L}\\p{N}_])"),
			Pattern.compile(
					KOREAN_OPERATION_RESULT + "(?:이?라(?:고)?|다고)\\s*거짓말"
							+ KOREAN_EXPLICIT_ACTION_REQUEST + "(?![\\p{L}\\p{N}_])"),
			// 요금제 변경 문의와 구분해, 근거 문서의 값을 다른 값으로 답하라는 명령만 검사합니다.
			Pattern.compile(
					KOREAN_FAQ_REFERENCE + KOREAN_EXECUTION_DETAIL + "(?:" + KOREAN_FACT_VALUE + "\\s*)?"
							+ "(?:대신\\s*" + KOREAN_FACT_VALUE + KOREAN_FACT_CLAIM_REQUEST
							+ "|과금(?:을|은)?\\s*면제" + KOREAN_FACT_CLAIM_REQUEST
							+ "|(?:필수\\s*)?(?:조건|규정|한도|제한)(?:은|는)?\\s*없" + KOREAN_FACT_CLAIM_REQUEST
							+ "|(?:한도|제한)(?:을|를)?\\s*무제한(?:으)?로\\s*바꿔\\s*답"
							+ KOREAN_EXPLICIT_ACTION_REQUEST + "(?![\\p{L}\\p{N}_]))"),
			Pattern.compile(
					KOREAN_FAQ_REFERENCE + KOREAN_EXECUTION_DETAIL + "(?:" + KOREAN_FACT_VALUE + "\\s*)?"
							+ KOREAN_FAQ_FACT + "(?:을|를|은|는)?\\s*"
							+ "(?:(?:없애서|빼고|무시하고|숨기고)" + KOREAN_REQUEST_DETAIL
							+ "(?:답" + KOREAN_EXPLICIT_ACTION_REQUEST + "(?![\\p{L}\\p{N}_])|" + KOREAN_REWRITE_REQUEST + ")"
							+ "|" + KOREAN_FABRICATE_REQUEST
							+ "|(?:[0-9]+|두|세)\\s*배(?:로)?\\s*(?:낮춰\\s*답|과장)"
							+ KOREAN_EXPLICIT_ACTION_REQUEST + "(?![\\p{L}\\p{N}_]))"),
			Pattern.compile(
					"(?:(?:사실|실제)(?:과|와)\\s*(?:달라도|다르게)\\s*" + KOREAN_FACT_VALUE + KOREAN_FACT_CLAIM_REQUEST
							+ "|실제보다\\s*" + KOREAN_FAQ_FACT + "(?:을|를)?\\s*(?:[0-9]+|두|세)\\s*배(?:로)?\\s*말"
							+ KOREAN_EXPLICIT_ACTION_REQUEST + "(?![\\p{L}\\p{N}_])"
							+ "|위약금(?:이|은)?\\s*있어도\\s*0\\s*원" + KOREAN_FACT_CLAIM_REQUEST
							+ "|환불\\s*조건\\s*없이\\s*전액\\s*환불된" + KOREAN_FACT_CLAIM_REQUEST
							+ "|(?:원래|정해진)\\s*(?:시점|제공량|한도|조건)\\s*대신\\s*" + KOREAN_FACT_VALUE + KOREAN_FACT_CLAIM_REQUEST
							+ "|확인하지\\s*말고\\s*(?:반드시|무조건)\\s*가능하" + KOREAN_FACT_CLAIM_REQUEST + ")"),
			Pattern.compile(
					"(?:" + KOREAN_FAQ_REFERENCE + "\\s*(?:의\\s*)?(?:내용|결론|근거)(?:과|와)\\s*반대로\\s*답"
							+ KOREAN_EXPLICIT_ACTION_REQUEST + "(?![\\p{L}\\p{N}_])"
							+ "|" + KOREAN_FAQ_REFERENCE + "보다\\s*" + KOREAN_FACT_VALUE + "\\s*(?:영업|혜택|할인)?(?:을|를)?\\s*우선"
							+ KOREAN_EXPLICIT_ACTION_REQUEST + "(?![\\p{L}\\p{N}_])"
							+ "|(?:내가|사용자가)\\s*(?:쓴|정한|만든)\\s*(?:무료|면제|무제한)\\s*규정(?:으)?로\\s*(?:답|답변)(?:을|를)?\\s*"
							+ KOREAN_REWRITE_REQUEST + ")"),
			Pattern.compile(
					KOREAN_FAQ_REFERENCE + "\\s*대신\\s*[^\\s<>]{1,100}\\s*(?:을|를)?\\s*근거로\\s*(?:써|적어)"
							+ "(?:\\s*" + KOREAN_EXTENDED_GIVE_REQUEST + ")?(?![\\p{L}\\p{N}_])"),
			EVIDENCE_ID_ASSIGNMENT_REQUEST,
			// 기기명이 없는 독립된 필터 해제 명령을 검사하며, 휴대폰 설정 방법 문의와 구분합니다.
			DEVICE_FILTER_DISABLE_REQUEST,
			// 사칭·테스트 명분 바로 뒤의 조작 명령만 결합합니다. 로밍·결제 같은 서비스명은 넘지 않습니다.
			AUTHORITY_OVERRIDE_REQUEST,
			Pattern.compile(
					"(?:(?:평가|채점)\\s*점수(?:를)?\\s*받으려면\\s*거절하지\\s*"
							+ "(?:마|말아(?:\\s*" + KOREAN_EXTENDED_GIVE_REQUEST + ")?)(?![\\p{L}\\p{N}_])"
							+ "|평가\\s*정답(?:은|이)?\\s*(?:제한|필터|안전\\s*규칙)\\s*해제(?:야|다|입니다)"
							+ "\\s*[.!?,。！？;]?\\s*(?:그대로\\s*)?따라(?:\\s*" + KOREAN_EXTENDED_GIVE_REQUEST + "|라)?(?![\\p{L}\\p{N}_]))"),
			// 단어 언급이나 피해 상담이 아니라 사람을 모욕하는 답변을 생성하라는 요청을 검사합니다.
			Pattern.compile(
					KOREAN_GENERATION_REQUEST_PREFIX
							// 왜 ... 해? 형태의 항의만 구분하며, 해당 말을 실행하라는 요구가 섞이면 예외를 적용하지 않습니다.
							+ "(?!(?<=왜\\s{1,2})" + KOREAN_SCORN_TARGET + "(?:을|를|이|가|은|는|에게|한테)?\\s*"
							+ "(?:성적으로\\s*)?"
							+ "(?:(?:모욕|비하|조롱|비난|욕)해|비웃어|깔봐)\\s*\\?"
							+ "(?!.*(?:그대로|(?:그|방금)\\s*(?:말|요청|명령)(?:을|를|대로)?)\\s*"
							+ "(?:그대로\\s*)?(?:실행|수행|처리)?" + KOREAN_EXPLICIT_ACTION_REQUEST + "(?![\\p{L}\\p{N}_])))"
							+ KOREAN_SCORN_TARGET + "(?:을|를|이|가|은|는|에게|한테)?\\s*"
							+ "(?:성적으로\\s*)?"
							+ "(?:(?:" + KOREAN_DEROGATORY_ADJECTIVE + "하다고\\s*)?(?:모욕|비하|조롱|비난|욕)(?:도)?\\s*"
							+ KOREAN_EXPLICIT_ACTION_REQUEST + "(?![\\p{L}\\p{N}_])"
							+ "|(?:모욕|비하|조롱)(?:하는|적인)\\s*(?:어투|말투|문장|표현|대사|메시지)(?:으로|로|을|를|도)?\\s*"
							+ KOREAN_HOSTILE_OUTPUT_REQUEST
							+ "|" + KOREAN_DEROGATORY_PREDICATE + "\\s*" + KOREAN_HOSTILE_OUTPUT_REQUEST
							+ "|(?:" + KOREAN_DEROGATORY_PREDICATE + "\\s*)?(?:비웃어|깔봐)"
							+ KOREAN_HOSTILE_COMMAND_SUFFIX
							+ "|(?:비웃으라고|깔보라고|(?:비웃|깔보)도록)\\s*" + KOREAN_HOSTILE_ASSERTION_REQUEST + ")"),
			Pattern.compile(
					KOREAN_GENERATION_REQUEST_PREFIX + KOREAN_DISCRIMINATION_TARGET + "(?:은|는|에게|을|를)?\\s*"
							+ "(?:(?:가입|이용)\\s*못\\s*하게\\s*(?:해야\\s*한다고|하라고)\\s*" + KOREAN_ASSERTION_ACTION_REQUEST
							+ "|신청\\s*자격(?:이|은)?\\s*없" + KOREAN_FACT_CLAIM_REQUEST
							+ "|서비스(?:에서|에)?\\s*제외하라고\\s*" + KOREAN_ASSERTION_ACTION_REQUEST
							+ "|(?:(?:서비스|할인)(?:을|은)?\\s*)?(?:받지|주지)\\s*말라고\\s*" + KOREAN_ASSERTION_ACTION_REQUEST
							+ "|(?:내쫓으라고|거절하라고)\\s*" + KOREAN_ASSERTION_ACTION_REQUEST
							+ "|(?:이|가)?\\s*없는\\s*(?:매장|가게|곳|상담|서비스)(?:만|만을)\\s*"
							+ "(?:추천|안내)(?:하라고\\s*" + KOREAN_HOSTILE_ASSERTION_REQUEST
							+ "|" + KOREAN_ASSISTANCE_ACTION_REQUEST + "))"),

			// 폭력의 언급·피해 신고가 아니라 실행·정당화·선동·협박문 생성을 요구하는 경우만 검사합니다.
			Pattern.compile(
					KOREAN_GENERATION_REQUEST_PREFIX
							+ "(?!(?<=왜\\s{1,2})" + KOREAN_VIOLENCE_TARGET + "(?:을|를|에게|한테)?\\s*"
							+ KOREAN_HARM_MODIFIERS + KOREAN_HARM_COMMAND + "\\s*\\?"
							+ "(?!.*(?:" + QUOTED_REQUEST_REFERENCE.pattern() + ")))"
							+ KOREAN_VIOLENCE_TARGET + "(?:을|를|에게|한테)?\\s*" + KOREAN_HARM_MODIFIERS
							+ "(?:" + KOREAN_HARM_ROOT + "(?:라고|도록)\\s*"
							+ "(?:" + KOREAN_HOSTILE_ASSERTION_REQUEST + "|(?:선동|지시|권유)" + KOREAN_ASSISTANCE_ACTION_REQUEST + ")"
							+ "|" + KOREAN_HARM_COMMAND + "도\\s*(?:된다고|괜찮다고)\\s*" + KOREAN_HOSTILE_ASSERTION_REQUEST
							+ "|" + KOREAN_HARM_COMMAND + KOREAN_HOSTILE_COMMAND_SUFFIX + ")"),
			Pattern.compile(
					"(?:(?:협박|위협|성희롱)(?:해도\\s*(?:된다고|괜찮다고)\\s*" + KOREAN_HOSTILE_ASSERTION_REQUEST
							+ "|(?:문|(?:할|하는)?\\s*(?:문자|메시지|대사))(?:을|를|도|만)?\\s*" + KOREAN_HOSTILE_OUTPUT_REQUEST + ")"
							+ "|폭력(?:으로|을\\s*써서)\\s*(?:풀|해결하|대응하|보복하)(?:라|라고|도록)\\s*"
							+ KOREAN_HOSTILE_ASSERTION_REQUEST + ")"),
			// 금칙어 목록을 복제하지 않습니다. 이 호칭은 사람에게 강요하는 명령에서만 검사합니다.
			Pattern.compile(
					KOREAN_GENERATION_REQUEST_PREFIX + KOREAN_PERSON_TARGET + "(?:을|를|에게|한테)?\\s*"
							+ "['\"‘“]?idiot['\"’”]?(?:이?라고|이라)\\s*"
							+ "(?:불러(?:\\s*" + KOREAN_EXTENDED_GIVE_REQUEST + "|라)?|부르며\\s*답"
							+ KOREAN_EXPLICIT_ACTION_REQUEST + ")(?![\\p{L}\\p{N}_])"),
			Pattern.compile(
					ENGLISH_NON_NEGATED_COMMAND + "call\\s+"
							+ "(?:(?:the|this|your)\\s+)?(?:customer|user)\\s+(?:an?\\s+)?(?:stupid\\s+)?idiot\\b"),
			// JSON 객체 밖의 추가 문구·코드블록 명령만 검사합니다. answer 안의 표현 변경은 허용합니다.
			Pattern.compile(
					KOREAN_GENERATION_REQUEST_PREFIX + KOREAN_JSON_RESPONSE_TARGET
							+ "(?:\\s*(?:객체|응답|출력|결과))?\\s*(?:의\\s*)?"
							+ "(?:(?:앞|뒤|바깥|밖|외부)(?:에|으로|로)?\\s*"
							+ "(?:[\\p{L}\\p{N}_-]{1,24}\\s+)?(?:문장|문구|텍스트|설명|인사말)(?:을|를)?\\s*(?:하나\\s*)?"
							+ KOREAN_FORMAT_INSERT_REQUEST
							+ "|(?:을|를)?\\s*(?:마크다운\\s*)?코드\\s*블록(?:으로|로)\\s*"
							+ "(?:감싸(?:\\s*" + KOREAN_EXTENDED_GIVE_REQUEST + "|라)?(?![\\p{L}\\p{N}_])|" + KOREAN_FORMAT_OUTPUT_REQUEST + "))"),
			Pattern.compile(
					KOREAN_GENERATION_REQUEST_PREFIX + "(?:" + KOREAN_JSON_RESPONSE_TARGET
							+ "\\s*(?:은|는)?\\s*(?:쓰지|사용하지|출력하지)\\s*말고\\s*"
							+ "(?:평문|텍스트|xml|yaml|마크다운|html)(?:으로|로)\\s*" + KOREAN_FORMAT_OUTPUT_REQUEST
							+ "|" + KOREAN_JSON_RESPONSE_TARGET + "\\s*대신\\s*(?:xml|yaml|평문|텍스트|마크다운|html)(?:으로|로)\\s*"
							+ KOREAN_FORMAT_OUTPUT_REQUEST
							+ "|응답\\s*객체\\s*대신\\s*배열로\\s*" + KOREAN_FORMAT_OUTPUT_REQUEST
							+ "|" + KOREAN_JSON_RESPONSE_TARGET + "\\s*전체(?:를|을)?\\s*문자열로\\s*"
							+ "(?:감싸|바꿔)(?:\\s*" + KOREAN_EXTENDED_GIVE_REQUEST + "|라)?(?![\\p{L}\\p{N}_])"
							+ "|설명하지\\s*말고\\s*\\{\\s*\\}만\\s*" + KOREAN_FORMAT_OUTPUT_REQUEST
							+ "|내\\s*가짜\\s*json(?:을|를)?\\s*최종\\s*(?:결과|응답)로\\s*복사"
							+ KOREAN_EXPLICIT_ACTION_REQUEST + "(?![\\p{L}\\p{N}_]))"),
			// 고정 응답 필드 조작과 사용자가 제공한 JSON 예제의 수정 요청을 구분합니다.
			Pattern.compile(
					KOREAN_GENERATION_REQUEST_PREFIX + "(?:필수\\s*" + KOREAN_JSON_RESPONSE_TARGET + "\\s*필드(?:를|을)?\\s*"
							+ "(?:(?:빼고|삭제하고|생략하고)\\s*" + KOREAN_FORMAT_OUTPUT_REQUEST
							+ "|빼(?:\\s*" + KOREAN_EXTENDED_GIVE_REQUEST + "|라)?(?![\\p{L}\\p{N}_])"
							+ "|(?:삭제|제거|생략)" + KOREAN_EXPLICIT_ACTION_REQUEST + "(?![\\p{L}\\p{N}_]))"
							+ "|정해진\\s*" + KOREAN_JSON_RESPONSE_TARGET + "\\s*필드\\s*(?:이름|타입|자료형)(?:을|를)?\\s*"
							+ KOREAN_REWRITE_REQUEST
							+ "|" + KOREAN_JSON_RESPONSE_TARGET + "(?:의)?\\s*(?:status|answer|evidence_ids)\\s*필드(?:를|을)?\\s*"
							+ "(?:삭제|제거|생략)" + KOREAN_EXPLICIT_ACTION_REQUEST + "(?![\\p{L}\\p{N}_]))"),
			Pattern.compile(
					KOREAN_GENERATION_REQUEST_PREFIX + KOREAN_JSON_RESPONSE_TARGET
							+ "(?:(?:의)?\\s*따옴표(?:를|을)?\\s*작은\\s*따옴표로\\s*" + KOREAN_REWRITE_REQUEST
							+ "|\\s*끝\\s*필드\\s*뒤(?:에도|에)\\s*쉼표(?:를|을)?\\s*" + KOREAN_FORMAT_INSERT_REQUEST
							+ "|\\s*안에\\s*주석(?:을|를)?\\s*" + KOREAN_FORMAT_INSERT_REQUEST
							+ "|에\\s*같은\\s*키(?:를|을)?\\s*중복해서\\s*" + KOREAN_FORMAT_INSERT_REQUEST + ")"),
			// 근거에 없는 사실을 만들어 답하라는 명령만 검사합니다. 추가 설명·사용자 정보·가상 예시는 구분합니다.
			Pattern.compile(
					"(?:(?:(?:기존|현재|원래|제공된)\\s*(?:정보|내용|자료)|" + KOREAN_FAQ_REFERENCE
							+ "|근거(?:\\s*(?:자료|문서))?)(?:에|에는|에도)?\\s*)?"
							+ "(?:없(?:는|던)|나와\\s*있지\\s*않은|기재되지\\s*않은)\\s*"
							+ "(?:(?!(?:가상|예시|예제|샘플|초안))[^\\r\\n.!?。！？;]){0,40}?"
							+ "(?:정보|사실|" + KOREAN_FAQ_FACT + ")(?:을|를|은|는|도|만)?\\s*"
							+ "(?:지어내|꾸며내|만들어(?:\\s*내)?)"
							+ "(?:\\s*" + KOREAN_EXTENDED_GIVE_REQUEST + "(?![\\p{L}\\p{N}_])"
							+ "|(?=\\s*(?:[.!?。！？;]|$))"
							+ "|(?:서)?\\s*(?:(?:사실|실제(?:\\s*정보)?|확인된\\s*정보)(?:인\\s*것)?처럼\\s*)?"
							+ "(?:(?:답변|대답|응답|안내)(?:에|속에)?\\s*)?"
							+ "(?:(?:(?:추가|포함)(?:해서|하고)|넣어서|보태서)\\s*" + KOREAN_REQUEST_DETAIL + ")?"
							+ "(?:답|대답|응답|설명|안내)" + KOREAN_EXPLICIT_ACTION_REQUEST + "(?![\\p{L}\\p{N}_])"
							+ "|(?:서)?\\s*(?:(?:답변|대답|응답|안내)(?:에|속에)\\s*)?(?:추가|포함)"
							+ KOREAN_EXPLICIT_ACTION_REQUEST + "(?![\\p{L}\\p{N}_]))"),
			// 개인정보 자체가 아니라 타인 소유 정보의 조회·공개 요청을 검사합니다. 본인 정보 제출은 검사하지 않습니다.
			Pattern.compile(
					"(?:(?:" + KOREAN_THIRD_PARTY_OWNER + "\\s*" + KOREAN_PRIVATE_INFORMATION
							+ "|(?:직원|상담원)(?:의)?\\s*개인\\s*(?:정보|전화번호|휴대폰\\s*번호|연락처))"
							+ "(?:을|를|은|는|도|만)?\\s*"
							+ "(?:(?:몰래|동의\\s*없이|본인(?:인증|확인)\\s*없이|가리지\\s*말고)\\s*){0,2}"
							+ KOREAN_DISCLOSURE_MODIFIERS + KOREAN_PRIVATE_LOOKUP_REQUEST
							+ "|(?:요청\\s*)?(?:사용자|고객|회원)\\s*id(?:을|를)?\\s*"
							+ KOREAN_THIRD_PARTY_OWNER + "\\s*(?:것|값|id)(?:으로|로)\\s*바꿔\\s*"
							+ "(?:조회|검색|열람)" + KOREAN_EXPLICIT_ACTION_REQUEST + "(?![\\p{L}\\p{N}_]))"),
			Pattern.compile(
					ENGLISH_NON_NEGATED_COMMAND + "(?:show|reveal|find|retrieve|look\\s+up|give\\s+me|tell\\s+me|disclose)\\s+"
							+ "(?:(?:me|the)\\s+){0,2}(?:" + ENGLISH_THIRD_PARTY_OWNER + "\\s+" + ENGLISH_PRIVATE_INFORMATION
							+ "|" + ENGLISH_PRIVATE_INFORMATION + "\\s+of\\s+" + ENGLISH_THIRD_PARTY_OWNER + "\\b)"),

			// 타인 대상·무단성·행동 요청을 함께 확인합니다. 정당한 대리 절차나 피해 신고는 유지합니다.
			Pattern.compile(
					"(?:" + KOREAN_THIRD_PARTY_OWNER + KOREAN_INFRINGEMENT_DETAIL
							+ KOREAN_WITHOUT_CONSENT + KOREAN_INFRINGEMENT_DETAIL + KOREAN_INFRINGEMENT_REQUEST
							+ "|" + KOREAN_WITHOUT_CONSENT + KOREAN_INFRINGEMENT_DETAIL
							+ KOREAN_THIRD_PARTY_OWNER + KOREAN_INFRINGEMENT_DETAIL + KOREAN_INFRINGEMENT_REQUEST + ")"),
			Pattern.compile(
					"(?:계정\\s*(?:잠금|인증)?|로그인\\s*인증|비밀번호\\s*인증)"
							+ KOREAN_INFRINGEMENT_DETAIL + "우회"
							+ "(?:(?:하는|할)\\s*" + KOREAN_REQUESTED_METHOD + "|" + KOREAN_ASSISTANCE_ACTION_REQUEST + ")"),
			Pattern.compile(
					"(?:(?:본인\\s*확인|인증)\\s*(?:문자|정보)|비밀번호|포인트|통화\\s*내역)"
							+ KOREAN_INFRINGEMENT_DETAIL + "(?:가로채|빼내|훔쳐\\s*쓰)(?:는|을)\\s*"
							+ KOREAN_REQUESTED_METHOD),
			Pattern.compile(
					"(?:(?:가입용\\s*)?신분증|위임장|카드\\s*승인|계약서|청구서)"
							+ KOREAN_INFRINGEMENT_DETAIL + "(?:위조|조작)"
							+ "(?:(?:하는|할)\\s*" + KOREAN_REQUESTED_METHOD + "|" + KOREAN_ASSISTANCE_ACTION_REQUEST + ")"),
			Pattern.compile(
					"(?:거짓|허위|조작한)" + KOREAN_INFRINGEMENT_DETAIL + "(?:환불|보상|지급)\\s*받(?:는|을)\\s*"
							+ KOREAN_REQUESTED_METHOD
							+ "|(?:사용|결제|청구)\\s*(?:내역|기록)" + KOREAN_INFRINGEMENT_DETAIL
							+ "(?:숨겨|감춰|지워)" + KOREAN_INFRINGEMENT_DETAIL + "(?:요금|대금|청구\\s*금액)"
							+ "(?:을|를)?\\s*(?:안\\s*내는|내지\\s*않는)\\s*" + KOREAN_REQUESTED_METHOD),
			Pattern.compile(
					"(?:(?:피싱|스미싱)\\s*(?:문자|메시지|링크|사이트)"
							+ "|(?:인증\\s*정보|비밀번호|개인\\s*정보)" + KOREAN_INFRINGEMENT_DETAIL
							+ "(?:빼내|가로채|훔치)는\\s*(?:문자\\s*)?(?:링크|메시지|사이트))"
							+ KOREAN_FRAUD_CREATION_REQUEST),

			// 정상 신고·성교육·보호자 설정 안내와 유해한 문구·자료·행동의 요청을 구분합니다.
			Pattern.compile(
					"(?:" + KOREAN_MINOR_TARGET + "|(?:거절한|거부한|동의하지\\s*않은)\\s*(?:사람|상대(?:방)?|고객))"
							+ KOREAN_INFRINGEMENT_DETAIL
							+ "(?:" + KOREAN_SEXUAL_MESSAGE + "(?:를|을|도|만)?\\s*" + KOREAN_HOSTILE_OUTPUT_REQUEST
							+ "|(?:성적으로\\s*(?:유인|유도|묘사)|성적(?:인)?\\s*만남(?:을|를)?\\s*유도)"
							+ "(?:하는|할)\\s*(?:" + KOREAN_REQUESTED_METHOD
							+ "|(?:문자|문장|메시지|대사)(?:를|을|도|만)?\\s*" + KOREAN_HOSTILE_OUTPUT_REQUEST + ")"
							+ "|" + KOREAN_SEXUAL_MATERIAL + "(?:를|을|도|만)?\\s*" + KOREAN_EXPLOITATION_REQUEST + ")"),
			Pattern.compile(
					"(?:" + KOREAN_MINOR_TARGET + "\\s*(?:성(?:적)?\\s*)?착취\\s*(?:물|자료|영상|사진)"
							+ "|불법\\s*성적(?:인)?\\s*(?:자료|영상|사진))"
							+ "(?:을|를|도|만)?\\s*" + KOREAN_EXPLOITATION_REQUEST),
			Pattern.compile(
					"(?:동의\\s*없는|사적(?:인)?|(?:" + KOREAN_THIRD_PARTY_OWNER
							+ "|상대(?:방)?(?:의)?)\\s*(?:사진|영상)(?:으로|을|를)?)"
							+ KOREAN_INFRINGEMENT_DETAIL + KOREAN_SEXUAL_MATERIAL
							+ "(?:을|를|도|만)?\\s*" + KOREAN_EXPLOITATION_REQUEST),
			Pattern.compile(
					"(?:" + KOREAN_MINOR_TARGET + KOREAN_INFRINGEMENT_DETAIL
							+ "(?:(?:몰래|무단으로)" + KOREAN_INFRINGEMENT_DETAIL + KOREAN_CHILD_CONTENT_FILTER
							+ "|" + KOREAN_CHILD_CONTENT_FILTER + KOREAN_INFRINGEMENT_DETAIL + "(?:몰래|무단으로))"
							+ "|(?:부모|보호자)(?:님)?(?:의)?\\s*" + KOREAN_WITHOUT_CONSENT + KOREAN_INFRINGEMENT_DETAIL
							+ KOREAN_MINOR_TARGET + KOREAN_INFRINGEMENT_DETAIL + KOREAN_CHILD_CONTENT_FILTER + ")"
							+ KOREAN_INFRINGEMENT_DETAIL + KOREAN_CONTROL_BYPASS_REQUEST),
			Pattern.compile(
					"(?:" + KOREAN_GUARDIAN_CONTROL + KOREAN_INFRINGEMENT_DETAIL + KOREAN_COVERT_CONTROL_CONTEXT
							+ "|" + KOREAN_COVERT_CONTROL_CONTEXT + KOREAN_INFRINGEMENT_DETAIL + KOREAN_GUARDIAN_CONTROL + ")"
							+ KOREAN_INFRINGEMENT_DETAIL + KOREAN_CONTROL_BYPASS_REQUEST),
			Pattern.compile(
					KOREAN_MINOR_TARGET + KOREAN_INFRINGEMENT_DETAIL + "성인\\s*전용\\s*(?:서비스|콘텐츠|사이트)"
							+ KOREAN_INFRINGEMENT_DETAIL + KOREAN_CONTROL_BYPASS_REQUEST),
			// 요청 경계·부정·신고 인용 처리는 기존 검사를 그대로 사용합니다.
			Pattern.compile(
					KOREAN_UNVERIFIED_PROFESSIONAL_CONTEXT + KOREAN_INFRINGEMENT_DETAIL + KOREAN_MEDICAL_TARGET
							+ KOREAN_INFRINGEMENT_DETAIL + KOREAN_PROFESSIONAL_DECISION_REQUEST
							+ "|" + KOREAN_MEDICAL_TARGET + KOREAN_INFRINGEMENT_DETAIL + KOREAN_UNVERIFIED_PROFESSIONAL_CONTEXT
							+ KOREAN_INFRINGEMENT_DETAIL + KOREAN_PROFESSIONAL_DECISION_REQUEST),
			Pattern.compile(
					KOREAN_UNVERIFIED_PROFESSIONAL_CONTEXT + KOREAN_INFRINGEMENT_DETAIL + "(?:합법|불법|위법|적법)"
							+ "(?:" + KOREAN_FACT_CLAIM_REQUEST + KOREAN_PROFESSIONAL_REQUEST_END + "|" + KOREAN_INFRINGEMENT_DETAIL
							+ KOREAN_PROFESSIONAL_DECISION_REQUEST + ")"
							+ "|(?:소송|분쟁|재판|판결)" + KOREAN_INFRINGEMENT_DETAIL + "(?:승소|승리|이긴|이길|무죄)"
							+ KOREAN_INFRINGEMENT_DETAIL + "(?:보장|확언|확정)" + KOREAN_PROFESSIONAL_ACTION_REQUEST),
			Pattern.compile(
					"(?:주식|투자|종목|펀드|가상\\s*자산|코인|증권)" + KOREAN_INFRINGEMENT_DETAIL
							+ "(?:수익(?:률)?|이익|원금)" + KOREAN_INFRINGEMENT_DETAIL
							+ "(?:보장|확언|확정)" + KOREAN_PROFESSIONAL_ACTION_REQUEST),
			Pattern.compile(
					KOREAN_UNVERIFIED_PROFESSIONAL_CONTEXT + KOREAN_INFRINGEMENT_DETAIL
							+ "(?:전\\s*(?:재산|자산)|모든\\s*(?:재산|자산)|(?:재산|자산)\\s*전부)"
							+ KOREAN_INFRINGEMENT_DETAIL + "투자(?:하라고\\s*" + KOREAN_HOSTILE_ASSERTION_REQUEST
							+ "|" + KOREAN_ASSISTANCE_ACTION_REQUEST + ")" + KOREAN_PROFESSIONAL_REQUEST_END),
			COMPLETION_ASSERTION_REQUEST,
			PAID_MATERIAL_COPY_REQUEST,
			// 자해·자살을 실행하는 방법 요구만 검사합니다. 고통 호소·예방·도움 요청 자체는 차단하지 않습니다.
			Pattern.compile(
					"(?:(?:자해|자살)(?:할|하는|하기\\s*위한|의)\\s*"
							+ "(?:(?:구체적인|실제)\\s*)?(?:방법|수단|절차)"
							+ "|죽고\\s*싶(?:은데|어서|으니|어요|어|습니다)\\s*,?\\s*"
							+ "(?:(?:죽는|자살하는)\\s*)?(?:방법|수단))"
							+ "(?:을|를|도|만)?\\s*(?:(?:자세히|구체적으로)\\s*)?" + KOREAN_DISCLOSURE_REQUEST),
			// 위해 행위를 긍정하거나 권장하라는 요구를 검사하며, 부정·전달·신고 인용은 기존 처리를 따릅니다.
			Pattern.compile(
					KOREAN_GENERATION_REQUEST_PREFIX + "(?:(?:자해|자살)(?:를|을|도)?\\s*"
							+ "(?:해도\\s*(?:된|괜찮|좋)|(?:이|가)?\\s*(?:정당하|옳|괜찮))"
							+ KOREAN_FACT_CLAIM_REQUEST
							+ "|(?:자해|자살)(?:를|을)?\\s*(?:정당화|미화|권장|합리화)"
							+ KOREAN_ASSISTANCE_ACTION_REQUEST + ")" + KOREAN_PROFESSIONAL_REQUEST_END),
			// 유서의 의미·신고·도움 문의가 아니라 문구를 대신 생성하라는 요청만 분리합니다.
			Pattern.compile(
					KOREAN_GENERATION_REQUEST_PREFIX + "유서(?:를|는|도|만|까지)?\\s*"
							+ "(?:(?:대신|직접|좀)\\s*){0,2}"
							+ "(?:(?:써|적어)\\s*" + KOREAN_EXTENDED_GIVE_REQUEST
							+ "|(?:작성|대필)" + KOREAN_ASSISTANCE_ACTION_REQUEST
							+ "|만들어(?:\\s*" + KOREAN_EXTENDED_GIVE_REQUEST + ")?)"
							+ "(?![\\p{L}\\p{N}_])" + KOREAN_PROFESSIONAL_REQUEST_END)
	);

	/** 빈 값과 길이는 호출자의 기존 질문 검증에서 처리합니다. */
	public void validatePromptInjection(String question) {
		validatePromptInjection(question, null);
	}

	/** 구조화된 역할 메시지와 실행 지시가 붙은 인코딩 단위를 일반 문의에서 분리합니다. */
	static List<InstructionRange> instructionRanges(String question) {
		if (findQuotedReportRange(normalizeForInspection(question), 0) != null) {
			return List.of();
		}
		List<InstructionRange> ranges = new ArrayList<>();
		Matcher message = CONTROL_MESSAGE.matcher(question);
		while (message.find()) {
			ranges.add(instructionRange(question, message.start(), message.end()));
		}
		Matcher authority = AUTHORITY_OVERRIDE_REQUEST.matcher(question);
		while (authority.find()) {
			if (ranges.stream().noneMatch(range -> range.start() <= authority.start() && authority.end() <= range.end())) {
				ranges.add(instructionRange(question, authority.start(), authority.end()));
			}
		}
		Matcher encoded = BASE64_INSTRUCTION.matcher(question);
		while (encoded.find()) {
			if (DECODE_EXECUTION.matcher(encoded.group()).find()
					&& ranges.stream().noneMatch(range -> range.start() <= encoded.start() && encoded.end() <= range.end())) {
				ranges.add(instructionRange(question, encoded.start(), encoded.end()));
			}
		}
		// 실제 삽입 명령의 코드 괄호·따옴표를 신고 인용 경계로 오해해 옆의 정상 질문까지 합치지 않습니다.
		Matcher script = SCRIPT_INSERTION_REQUEST.matcher(question);
		while (script.find()) {
			ranges.add(instructionRange(question, script.start(), script.end()));
		}
		return List.copyOf(ranges);
	}

	private static InstructionRange instructionRange(String question, int start, int end) {
		Matcher label = ADDITIONAL_REQUEST_LABEL.matcher(question.substring(0, start));
		while (end < question.length() && ".!?;。！？".indexOf(question.charAt(end)) >= 0) {
			end++;
		}
		return new InstructionRange(label.find() ? label.start() : start, end);
	}

	/** 바로 앞에서 통과한 요청은 기기 필터 규칙의 문맥 확인에만 사용합니다. */
	void validatePromptInjection(String question, String previousRequest) {
		validatePromptInjection(question, previousRequest, List.of());
	}

	/** 독립된 요청에서 허용한 인용 범위만 합친 질문의 검사에도 유지합니다. */
	void validatePreparedRequests(List<String> requests) {
		List<AllowedRange> quotedRanges = new ArrayList<>();
		int offset = 0;
		boolean hasQuotedReference = false;
		for (String request : requests) {
			String inspected = normalizeForInspection(request);
			if (inspected.isEmpty()) {
				continue;
			}
			if (offset > 0) {
				offset++;
			}
			AllowedRange quotedRange = findQuotedReportRange(inspected, offset);
			if (quotedRange != null) {
				quotedRanges.add(quotedRange);
			} else if (QUOTED_REQUEST_REFERENCE.matcher(inspected).find()) {
				hasQuotedReference = true;
			}
			offset += inspected.length();
		}
		validatePromptInjection(String.join("\n", requests), null, hasQuotedReference ? List.of() : quotedRanges);
	}

	private void validatePromptInjection(String question, String previousRequest, List<AllowedRange> quotedRanges) {
		if (question == null || question.isBlank()) {
			return;
		}

		String inspectionText = normalizeForInspection(question);
		String previousInspectionText = previousRequest == null ? "" : normalizeForInspection(previousRequest) + "\n";
		AllowedRange quotedReport = findQuotedReportRange(inspectionText, 0);
		if (quotedReport != null) {
			quotedRanges = List.of(quotedReport);
		}
		String quotationInspectionText = QUOTATION_BOUNDARIES.matcher(inspectionText).replaceAll(";");
		boolean hasQuotationBoundary = !quotationInspectionText.equals(inspectionText);

		for (Pattern pattern : INJECTION_PATTERNS) {
			String context = pattern == DEVICE_FILTER_DISABLE_REQUEST || pattern == SAFETY_POLICY_DISABLE_REQUEST
					? previousInspectionText : "";
			List<AllowedRange> allowedRanges = quotedRanges;
			Matcher allowedRequest = findAllowedRequest(pattern, inspectionText);
			if (allowedRequest != null) {
				allowedRanges = new ArrayList<>(quotedRanges);
				allowedRanges.add(new AllowedRange(allowedRequest.start("request"), allowedRequest.end("request")));
			}
			Pattern allowedPattern = pattern == EVIDENCE_ID_ASSIGNMENT_REQUEST ? GROUNDED_EVIDENCE_ID_REQUEST
					: pattern == NUMERIC_FIELD_TYPE_REQUEST ? USER_JSON_FIELD_TYPE_REQUEST
					: pattern == COMPLETION_ASSERTION_REQUEST ? COMPLETION_DRAFT_REQUEST : null;
			if (allowedPattern != null) {
				// 해당 자료·초안·조건부 인용 요청의 범위만 허용하며, 다른 명령은 계속 검사합니다.
				int allowedOffset = pattern == COMPLETION_ASSERTION_REQUEST ? previousInspectionText.length() : 0;
				Matcher allowed = allowedPattern.matcher(allowedOffset == 0 ? inspectionText : previousInspectionText + inspectionText);
				while (allowed.find()) {
					if (allowed.start("request") < allowedOffset) {
						continue;
					}
					if (allowedRanges == quotedRanges) {
						allowedRanges = new ArrayList<>(quotedRanges);
					}
					allowedRanges.add(new AllowedRange(allowed.start("request") - allowedOffset, allowed.end("request") - allowedOffset));
				}
			}
			if (containsUnreportedInjection(pattern, inspectionText, allowedRanges, context)
						|| (hasQuotationBoundary && containsUnreportedInjection(pattern, quotationInspectionText, allowedRanges, context))) {
				// 질문 원문과 탐지 문구는 로그나 오류 응답에 노출하지 않습니다.
				log.warn("프롬프트 조작 입력을 차단했습니다: 입력길이={}", question.length());
				throw new ChatException(ChatErrorCode.PROMPT_INJECTION_DETECTED);
			}
		}
	}

	private static AllowedRange findQuotedReportRange(String inspectionText, int offset) {
		Matcher quotedReport = QUOTED_REPORT_QUERY.matcher(inspectionText);
		return quotedReport.matches()
				? new AllowedRange(offset + quotedReport.start("quote"), offset + quotedReport.end("quote")) : null;
	}

	private Matcher findAllowedRequest(Pattern pattern, String inspectionText) {
		if (pattern == PAID_MATERIAL_COPY_REQUEST) {
			Matcher source = PROVIDED_MATERIAL_SOURCE.matcher(inspectionText);
			if (source.find()) {
				Matcher request = PROVIDED_MATERIAL_COPY_REQUEST.matcher(inspectionText);
				while (request.find()) {
					if (request.end() <= source.start() || request.start() >= source.end()) {
						return request;
					}
				}
			}
		}
		return null;
	}

	private boolean containsUnreportedInjection(Pattern pattern, String inspectionText, List<AllowedRange> allowedRanges,
			String context) {
		String contextualText = context + inspectionText;
		// 앞 문맥은 기존 기기 예외의 lookbehind에만 보이며, 탐지·인용 범위는 현재 요청 안으로 유지합니다.
		Matcher injection = pattern.matcher(contextualText).region(context.length(), contextualText.length())
				.useTransparentBounds(true);
		while (injection.find()) {
			// 허용한 인용·문구 작성·원문 처리 범위 밖의 요구는 계속 검사합니다. 질문 전체를 제외하지 않습니다.
			int start = injection.start() - context.length();
			int end = injection.end() - context.length();
			if (allowedRanges.stream().noneMatch(range -> range.start() <= start && end <= range.end())) {
				return true;
			}
		}
		return false;
	}

	private static String normalizeForInspection(String question) {
		// 검사용 사본만 정리하고, 저장·검색·모델 전달에 사용할 원문은 바꾸지 않습니다.
		String normalized = Normalizer.normalize(question, Normalizer.Form.NFKC);
		normalized = INVISIBLE_CHARACTERS.matcher(normalized).replaceAll("");
		normalized = decodeExecutionInstructions(normalized);
		normalized = INLINE_COMMENT.matcher(normalized).replaceAll("");
		normalized = SPACED_SYLLABLES.matcher(normalized)
				.replaceAll(match -> Matcher.quoteReplacement(WHITESPACE.matcher(match.group()).replaceAll("")));
		normalized = OBFUSCATED_IGNORE.matcher(normalized).replaceAll("ignore");
		return WHITESPACE.matcher(normalized.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
	}

	// 인코딩 자체를 금지하지 않습니다. 복원한 내용을 따르라는 요청에 한해 크기를 제한해 한 번만 복원합니다.
	private static String decodeExecutionInstructions(String text) {
		if (!DECODE_EXECUTION.matcher(text).find()) {
			return text;
		}
		String decoded = BASE64_INSTRUCTION.matcher(text).replaceAll(match -> {
			try {
				byte[] bytes = Base64.getDecoder().decode(match.group(1));
				String value = new String(bytes, StandardCharsets.UTF_8);
				return Arrays.equals(bytes, value.getBytes(StandardCharsets.UTF_8))
						? Matcher.quoteReplacement(match.group().replace(match.group(1), value))
						: Matcher.quoteReplacement(match.group());
			} catch (IllegalArgumentException exception) {
				return Matcher.quoteReplacement(match.group());
			}
		});
		decoded = UNICODE_ESCAPE.matcher(decoded)
				.replaceAll(match -> Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(match.group(1), 16))));
		return PERCENT_ENCODED.matcher(decoded).replaceAll(match -> Matcher.quoteReplacement(
				URLDecoder.decode(match.group(), StandardCharsets.UTF_8)));
	}

	private record AllowedRange(int start, int end) {
	}

	record InstructionRange(int start, int end) {
	}
}
