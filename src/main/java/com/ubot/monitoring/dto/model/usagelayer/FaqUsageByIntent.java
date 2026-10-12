package com.ubot.monitoring.dto.model.usagelayer;

import com.ubot.faq.enums.Intent;

import java.util.List;

public record FaqUsageByIntent (
		Intent intent,
		List<FaqUsageItem> top,
		List<FaqUsageItem> low
){
}
