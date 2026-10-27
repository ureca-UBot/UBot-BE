package com.ubot.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.subscription.exception.SubscriptionErrorCode;
import com.ubot.subscription.exception.SubscriptionException;
import com.ubot.subscription.service.BundleGroupService;
import com.ubot.subscription.service.SubscriptionCommandService;
import com.ubot.user.entity.User;
import com.ubot.user.enums.UserRole;
import com.ubot.user.repository.UserRepository;

// 두 트랜잭션이 각자 커밋해야 하므로 테스트 트랜잭션으로 묶지 않는다.
@SpringBootTest(properties = "data-retention.purge-cron=-")
@ActiveProfiles("test")
@Import(PgvectorTestConfiguration.class)
@DisplayName("결합 회선 추가와 회선 해지 동시성 통합 테스트")
class BundleGroupConcurrencyTest {

	@Autowired
	private BundleGroupService bundleGroupService;
	@Autowired
	private SubscriptionCommandService subscriptionCommandService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private PlatformTransactionManager transactionManager;

	private User owner;
	private long representativeLine;
	private long memberLine;
	private String memberPhone;
	private long groupId;

	@BeforeEach
	void createFixtures() {
		LocalDateTime now = LocalDateTime.now();
		owner = userRepository.saveAndFlush(User.builder().email(UUID.randomUUID() + "@test.com")
				.hashedPassword("test-only").name("결합 동시성 테스트 사용자").role(UserRole.USER)
				.createdAt(now).updatedAt(now).build());
		long planId = jdbc.queryForObject("""
				INSERT INTO plans (plan_code, name, network_type, target_group, monthly_fee,
				    data_amount_mb, voice_minutes, sms_count)
				VALUES (?, '결합 동시성 테스트 요금제', 'FIVE_G', 'GENERAL', 30000, 1000, 100, 100)
				RETURNING plan_id
				""", Long.class, UUID.randomUUID().toString().substring(0, 30));
		representativeLine = createSubscription(planId, randomPhone());
		memberPhone = randomPhone();
		memberLine = createSubscription(planId, memberPhone);
		long family3 = jdbc.queryForObject(
				"SELECT bundle_product_id FROM bundle_products WHERE code = 'UBOT_BUNDLE_FAMILY_3'", Long.class);
		groupId = bundleGroupService.create(owner.getId(), representativeLine, family3).bundleGroupId();
	}

	@Test
	@DisplayName("해지가 먼저 회선을 잠그면 회선 추가는 해지 커밋을 기다린 뒤 추가할 수 없는 회선으로 거절된다")
	void terminationCommitsBeforeAddingMember() throws Exception {
		CountDownLatch terminationReady = new CountDownLatch(1);
		CountDownLatch commitTermination = new CountDownLatch(1);
		CountDownLatch additionStarted = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<?> termination = executor.submit(() -> inTransaction(() -> {
				subscriptionCommandService.terminateSubscription(owner.getId(), memberLine);
				terminationReady.countDown();
				await(commitTermination);
			}));
			try {
				assertThat(terminationReady.await(10, TimeUnit.SECONDS)).isTrue();
				Future<?> addition = executor.submit(() -> {
					additionStarted.countDown();
					return bundleGroupService.addMember(owner.getId(), representativeLine, groupId, memberPhone);
				});
				assertThat(additionStarted.await(10, TimeUnit.SECONDS)).isTrue();
				assertThatThrownBy(() -> addition.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
				commitTermination.countDown();
				termination.get(10, TimeUnit.SECONDS);
				ExecutionException rejected = assertThrows(ExecutionException.class, () -> addition.get(10, TimeUnit.SECONDS));
				assertThat(rejected.getCause()).isInstanceOfSatisfying(SubscriptionException.class,
						exception -> assertThat(exception.getErrorCode()).isEqualTo(SubscriptionErrorCode.BUNDLE_MEMBER_NOT_ADDABLE));
			} finally {
				commitTermination.countDown();
			}
		}
		assertThat(activeBundleCount(memberLine)).isZero();
	}

	@Test
	@DisplayName("회선 추가가 먼저 회선을 잠그면 해지는 추가 커밋을 기다린 뒤 그 회선을 결합에서 뺀다")
	void additionCommitsBeforeTermination() throws Exception {
		CountDownLatch additionReady = new CountDownLatch(1);
		CountDownLatch commitAddition = new CountDownLatch(1);
		CountDownLatch terminationStarted = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<?> addition = executor.submit(() -> inTransaction(() -> {
				bundleGroupService.addMember(owner.getId(), representativeLine, groupId, memberPhone);
				additionReady.countDown();
				await(commitAddition);
			}));
			try {
				assertThat(additionReady.await(10, TimeUnit.SECONDS)).isTrue();
				Future<?> termination = executor.submit(() -> {
					terminationStarted.countDown();
					return subscriptionCommandService.terminateSubscription(owner.getId(), memberLine);
				});
				assertThat(terminationStarted.await(10, TimeUnit.SECONDS)).isTrue();
				assertThatThrownBy(() -> termination.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
				commitAddition.countDown();
				addition.get(10, TimeUnit.SECONDS);
				termination.get(10, TimeUnit.SECONDS);
			} finally {
				commitAddition.countDown();
			}
		}
		assertThat(activeBundleCount(memberLine)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM subscription_bundles WHERE subscription_id = ?",
				Integer.class, memberLine)).isOne();
	}

	private void inTransaction(Runnable work) {
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
	}

	private int activeBundleCount(long subscriptionId) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM subscription_bundles WHERE subscription_id = ? AND status = 'ACTIVE'",
				Integer.class, subscriptionId);
	}

	private long createSubscription(long planId, String phoneNumber) {
		return jdbc.queryForObject("""
				INSERT INTO subscriptions (user_id, plan_id, phone_number, status, activated_at)
				VALUES (?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP - INTERVAL '1 year')
				RETURNING subscription_id
				""", Long.class, owner.getId(), planId, phoneNumber);
	}

	// 시드 회선(010-)과 겹치지 않는 번호
	private String randomPhone() {
		return "019" + ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000);
	}

	private static void await(CountDownLatch latch) {
		try {
			assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(exception);
		}
	}
}
