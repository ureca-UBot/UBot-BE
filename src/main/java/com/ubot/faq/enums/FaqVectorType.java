package com.ubot.faq.enums;

/** faq_embeddings.vector_type에 저장하는 값입니다. 유형마다 임베딩에 넣는 텍스트가 다릅니다. */
public enum FaqVectorType {
	QUESTION(false),
	QUESTION_ANSWER(true);

	private final boolean includesAnswer;

	FaqVectorType(boolean includesAnswer) {
		this.includesAnswer = includesAnswer;
	}

	/** 임베딩할 텍스트를 만듭니다. FAQ 생성, 수정, 백필이 모두 이 형식을 씁니다. */
	public String toEmbeddingText(String question, String answer) {
		if (!includesAnswer) {
			return question;
		}

		return "질문: " + question + "\n답변: " + answer;
	}
}
