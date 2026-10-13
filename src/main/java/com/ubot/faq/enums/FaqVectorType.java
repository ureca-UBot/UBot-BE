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

	/** FAQ의 질문이나 답변이 바뀌었을 때 이 유형의 벡터를 다시 만들어야 하는지 알려줍니다. */
	public boolean needsReembedding(boolean questionChanged, boolean answerChanged) {
		return questionChanged || (includesAnswer && answerChanged);
	}
}
