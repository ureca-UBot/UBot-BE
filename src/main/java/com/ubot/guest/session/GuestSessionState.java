package com.ubot.guest.session;

import java.io.Serializable;

/**
 * 게스트 세션에 저장하는 상태 객체입니다. 게스트 대화 식별자, 이용 횟수 등은
 * 게스트 기능을 구현할 때 이 객체에 추가합니다. 질문·답변 본문은 담지 않고 DB에서 관리합니다.
 */
public class GuestSessionState implements Serializable {
	private static final long serialVersionUID = 1L;
}
