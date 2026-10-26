package com.ubot.counsel;
import com.ubot.PgvectorTestConfiguration;
import com.ubot.RedisTestConfiguration;
import com.ubot.auth.util.JwtUtil;
import com.ubot.counsel.auth.model.Caller;
import com.ubot.counsel.session.service.SessionService;
import com.ubot.user.entity.User;
import com.ubot.user.enums.UserRole;
import com.ubot.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PgvectorTestConfiguration.class, RedisTestConfiguration.class})
@Transactional
class CounselCustomerApiTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JwtUtil jwt;
    @Autowired SessionService sessions;
    private User user(UserRole role) {
        return users.saveAndFlush(User.builder().name("연결 고객").email(UUID.randomUUID()+"@test.invalid")
            .hashedPassword("not-a-login").role(role).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
    }
    @Test void customerEndpointAllowsOnlyTheAssignedCounselorAndDoesNotExposeOtherProfileFields() throws Exception {
        var customer=user(UserRole.USER);
        var counselor=user(UserRole.COUNSELOR);
        var other=user(UserRole.COUNSELOR);
        sessions.updateAvailability(new Caller(counselor.getId(),UserRole.COUNSELOR),"AVAILABLE");
        var session=sessions.createSession(new Caller(customer.getId(),UserRole.USER));
        String path="/counsel/sessions/"+session.sessionId()+"/customer";
        mvc.perform(get(path).header("Authorization","Bearer "+jwt.createAccessToken(counselor)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("연결 고객"))
            .andExpect(jsonPath("$.data.lines").isArray()).andExpect(jsonPath("$.data.homeProducts").isArray())
            .andExpect(jsonPath("$.data.email").doesNotExist()).andExpect(jsonPath("$.data.address").doesNotExist());
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization","Bearer "+jwt.createAccessToken(customer))).andExpect(status().isForbidden());
        mvc.perform(get(path).header("Authorization","Bearer "+jwt.createAccessToken(other))).andExpect(status().isForbidden());
        sessions.endSession(new Caller(counselor.getId(),UserRole.COUNSELOR),session.sessionId());
        mvc.perform(get(path).header("Authorization","Bearer "+jwt.createAccessToken(counselor))).andExpect(status().isConflict());
    }
}
