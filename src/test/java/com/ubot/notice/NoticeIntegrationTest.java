package com.ubot.notice;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.chat.util.IpRegionResolver;
import com.ubot.notice.entity.Notice;
import com.ubot.notice.enums.NoticeType;
import com.ubot.notice.repository.NoticeRepository;
import com.ubot.ranking.enums.RegionSido;
import com.ubot.user.entity.User;
import com.ubot.user.enums.UserRole;
import com.ubot.user.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PgvectorTestConfiguration.class)
@Transactional
@DisplayName("공지·장애 조회 통합 테스트")
class NoticeIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private NoticeRepository noticeRepository;
    @Autowired
    private UserRepository userRepository;

    // 테스트 환경에서는 IP로 지역을 알 수 없으므로 판별 결과를 직접 정합니다.
    @MockitoBean
    private IpRegionResolver ipRegionResolver;

    private User administrator;

    @BeforeEach
    void createAdministrator() {
        LocalDateTime now = LocalDateTime.now();
        administrator = userRepository.saveAndFlush(User.builder()
                .email("notice-" + UUID.randomUUID() + "@test.com")
                .hashedPassword("test-only")
                .name("공지 관리자")
                .role(UserRole.ADMIN)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    @Test
    @DisplayName("요청 IP의 지역이 서울이면 전체 공지와 서울 공지만 배너에 나오고 만료·삭제된 공지는 제외된다")
    void filtersBannerByRegionExpiryAndDeletion() throws Exception {
        when(ipRegionResolver.resolve(any())).thenReturn(Optional.of(RegionSido.SEOUL));
        save(NoticeType.NOTICE, "전체 공지", null, null);
        save(NoticeType.NOTICE, "서울 공지", RegionSido.SEOUL, null);
        save(NoticeType.NOTICE, "부산 공지", RegionSido.BUSAN, null);
        save(NoticeType.NOTICE, "만료 공지", null, LocalDateTime.now().minusHours(1));
        save(NoticeType.NOTICE, "미래 종료 공지", null, LocalDateTime.now().plusHours(1));
        Notice deleted = save(NoticeType.NOTICE, "삭제 공지", null, null);
        deleted.delete(administrator);
        noticeRepository.saveAndFlush(deleted);
        save(NoticeType.OUTAGE, "서울 장애", RegionSido.SEOUL, null);

        mockMvc.perform(get("/notices/banners"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notices.length()").value(3))
                .andExpect(jsonPath("$.data.notices[0].title").value("미래 종료 공지"))
                .andExpect(jsonPath("$.data.notices[1].title").value("서울 공지"))
                .andExpect(jsonPath("$.data.notices[2].title").value("전체 공지"))
                .andExpect(jsonPath("$.data.outages.length()").value(1))
                .andExpect(jsonPath("$.data.outages[0].title").value("서울 장애"));
    }

    @Test
    @DisplayName("지역을 판별하지 못하면 전체 공지만 배너에 나온다")
    void returnsOnlyGlobalNoticesWithoutRegion() throws Exception {
        when(ipRegionResolver.resolve(any())).thenReturn(Optional.empty());
        save(NoticeType.NOTICE, "전체 공지", null, null);
        save(NoticeType.NOTICE, "서울 공지", RegionSido.SEOUL, null);

        mockMvc.perform(get("/notices/banners").header("X-Real-IP", "10.0.0.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notices.length()").value(1))
                .andExpect(jsonPath("$.data.notices[0].title").value("전체 공지"));
    }

    @Test
    @DisplayName("배너는 종류별로 최대 10개만 내려준다")
    void limitsBannerToTen() throws Exception {
        when(ipRegionResolver.resolve(any())).thenReturn(Optional.of(RegionSido.SEOUL));
        for (int i = 0; i < 12; i++) {
            save(NoticeType.NOTICE, "공지 " + i, null, null);
        }

        mockMvc.perform(get("/notices/banners"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notices.length()").value(10))
                .andExpect(jsonPath("$.data.notices[0].title").value("공지 11"));
    }

    @Test
    @DisplayName("목록은 만료된 공지도 이력으로 포함하고 삭제된 공지는 제외한다")
    void listIncludesExpiredButNotDeleted() throws Exception {
        save(NoticeType.NOTICE, "만료 공지", null, LocalDateTime.now().minusDays(1));
        Notice deleted = save(NoticeType.NOTICE, "삭제 공지", null, null);
        deleted.delete(administrator);
        noticeRepository.saveAndFlush(deleted);

        mockMvc.perform(get("/notices").param("type", "NOTICE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].title").value("만료 공지"));
    }

    @Test
    @DisplayName("삭제된 공지의 상세 조회는 404를 반환한다")
    void detailOfDeletedNoticeIsNotFound() throws Exception {
        Notice notice = save(NoticeType.NOTICE, "삭제 공지", null, null);
        mockMvc.perform(get("/notices/" + notice.getNoticeId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("내용"));

        notice.delete(administrator);
        noticeRepository.saveAndFlush(notice);

        mockMvc.perform(get("/notices/" + notice.getNoticeId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTICE-001"));
    }

    private Notice save(NoticeType type, String title, RegionSido region, LocalDateTime endsAt) {
        return noticeRepository.saveAndFlush(Notice.create(administrator, type, title, "내용", region, endsAt));
    }
}