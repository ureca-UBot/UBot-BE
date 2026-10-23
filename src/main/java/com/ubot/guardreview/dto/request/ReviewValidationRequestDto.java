package com.ubot.guardreview.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import com.ubot.guardreview.model.ReviewModels.Approval;

public record ReviewValidationRequestDto(@NotEmpty @Size(max = 50) List<@NotNull @Valid Approval> selection) {
}
