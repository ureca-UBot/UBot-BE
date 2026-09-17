package com.ubot.faq.dto.reqeust;

public record FaqUpdateRequestDto(
		Long id,
		String category,
		String question,
		String answer
){
}
