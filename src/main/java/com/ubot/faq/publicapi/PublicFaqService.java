package com.ubot.faq.publicapi;
import com.ubot.common.GlobalException;
import com.ubot.common.PageResponseDto;
import com.ubot.common.exception.CommonErrorCode;
import com.ubot.faq.exception.FaqErrorCode;
import com.ubot.faq.exception.FaqException;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicFaqService {
    private final PublicFaqRepository repository;
    public List<PublicFaqResponse.Category> categories() {
        return repository.categories().stream().map(row -> new PublicFaqResponse.Category(row.getName(), row.getCount())).toList();
    }
    public PageResponseDto<PublicFaqResponse.Summary> list(String category, int page, int size) {
        if (page < 0 || !Set.of(10, 20, 50).contains(size)) throw new GlobalException(CommonErrorCode.INVALID_PARAMETER);
        String normalized = category == null || category.isBlank() ? null : category.strip();
        return PageResponseDto.from(repository.list(normalized, PageRequest.of(page, size))
            .map(row -> new PublicFaqResponse.Summary(row.getId(), row.getCategory(), row.getQuestion())));
    }
    public PublicFaqResponse.Detail detail(Long id) {
        var row = repository.detail(id).orElseThrow(() -> new FaqException(FaqErrorCode.FAQ_NOT_FOUND));
        return new PublicFaqResponse.Detail(row.getId(), row.getCategory(), row.getQuestion(), row.getAnswer());
    }
}
