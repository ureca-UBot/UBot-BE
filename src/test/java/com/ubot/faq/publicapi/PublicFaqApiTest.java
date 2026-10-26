package com.ubot.faq.publicapi;
import com.ubot.PgvectorTestConfiguration;
import com.ubot.faq.entity.Faq;
import com.ubot.faq.entity.FaqCategory;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.repository.FaqRepository;
import com.ubot.faq.repository.FaqCategoryRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PgvectorTestConfiguration.class)
@Transactional
class PublicFaqApiTest {
    @Autowired MockMvc mvc;
    @Autowired FaqRepository faqs;
    @Autowired FaqCategoryRepository categories;
    private FaqCategory category(String name) {
        var now=LocalDateTime.now();
        return categories.saveAndFlush(FaqCategory.builder().name(name).createdAt(now).updatedAt(now).build());
    }
    private Faq faq(FaqCategory category, String question) {
        var now=LocalDateTime.now();
        return faqs.saveAndFlush(Faq.builder().faqCategory(category).question(question).answer("공개 답변")
            .intent(Intent.GENERAL).createdAt(now).updatedAt(now).build());
    }
    @Test void guestsCanGroupFilterPageAndReadOnlyPublicFields() throws Exception {
        String prefix="와이파이-"+UUID.randomUUID();
        var first=faq(category(" "+prefix+" /QR 공유"),"QR 공유 질문");
        var second=faq(category(prefix+"/암호/변경"),"암호 질문");
        String plain="문자-"+UUID.randomUUID();
        faq(category(plain),"문자 질문");
        String empty="빈 카테고리-"+UUID.randomUUID();
        category(empty);
        var result=mvc.perform(get("/public/faqs/categories")).andExpect(status().isOk()).andReturn();
        var nodes=new ObjectMapper().readTree(result.getResponse().getContentAsString()).path("data");
        boolean grouped=false, unchanged=false, zero=false;
        for(var node:nodes) {
            if(node.path("name").asText().equals(prefix)) { grouped=true; assertThat(node.path("count").asLong()).isEqualTo(2); }
            if(node.path("name").asText().equals(plain)) unchanged=true;
            if(node.path("name").asText().equals(empty)) { zero=true; assertThat(node.path("count").asLong()).isZero(); }
        }
        assertThat(grouped && unchanged && zero).isTrue();
        mvc.perform(get("/public/faqs").param("category",prefix).param("size","10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2))
            .andExpect(jsonPath("$.data.content[0].id").value(first.getId()))
            .andExpect(jsonPath("$.data.content[1].id").value(second.getId()))
            .andExpect(jsonPath("$.data.content[0].category").value(prefix))
            .andExpect(jsonPath("$.data.content[0].answer").doesNotExist())
            .andExpect(jsonPath("$.data.content[0].adminId").doesNotExist());
        mvc.perform(get("/public/faqs/"+first.getId()).header("Authorization","Bearer expired-fixture"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.answer").value("공개 답변"))
            .andExpect(jsonPath("$.data.category").value(prefix))
            .andExpect(jsonPath("$.data.intent").doesNotExist())
            .andExpect(jsonPath("$.data.adminId").doesNotExist());
        mvc.perform(get("/public/faqs").param("category",prefix).param("page","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.content.length()").value(0));
        mvc.perform(get("/public/faqs").param("size","500")).andExpect(status().isBadRequest());
        mvc.perform(get("/admin/faqs")).andExpect(status().isUnauthorized());
        mvc.perform(post("/public/faqs")).andExpect(status().isUnauthorized());
    }
    @Test void deletedFaqOrCategoryIsNeverPublic() throws Exception {
        var category=category("삭제 검증-"+UUID.randomUUID());
        var removed=faq(category,"삭제 FAQ");
        removed.delete();faqs.saveAndFlush(removed);
        var hidden=faq(category,"삭제 카테고리 FAQ");
        category.delete();categories.saveAndFlush(category);
        mvc.perform(get("/public/faqs/"+removed.getId())).andExpect(status().isNotFound());
        mvc.perform(get("/public/faqs/"+hidden.getId())).andExpect(status().isNotFound());
        mvc.perform(get("/public/faqs").param("category",category.getName()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
    }
}
