package com.ubot.faq.dto.reqeust;

import com.pgvector.PGvector;

public record FaqCreateRequestDto(
		String category,
		String question,
		String answer
){
}
