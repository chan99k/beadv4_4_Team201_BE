package app.giftify.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import app.giftify.shared.domain.event.member.MemberSignedEvent;
import app.giftify.support.common.AbstractRedisTest;
import app.giftify.support.common.annotation.EventIdempotent;
import giftify.support.web.idempotency.IdempotencyStatus;
import giftify.support.web.idempotency.manager.IdempotencyManager;

@SpringBootTest(
	classes = EventIdempotencyTestApp.class,
	webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Import(EventIdempotencyIntegrationTest.TestListenerConfig.class)
@EmbeddedKafka(partitions = 1)
@TestPropertySource(properties = {
	"spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
})
class EventIdempotencyIntegrationTest extends AbstractRedisTest {

	private static final String KEY_PREFIX = "EVENT_IDEM:IT:";

	@TestConfiguration
	static class TestListenerConfig {
		@Bean
		IdempotentTestListener idempotentTestListener() {
			return new IdempotentTestListener();
		}
	}

	public static class IdempotentTestListener {
		private final AtomicInteger invocations = new AtomicInteger();

		@ApplicationModuleListener
		@EventIdempotent(prefix = "IT")
		public void on(MemberSignedEvent event) {
			invocations.incrementAndGet();
		}

		int invocations() {
			return invocations.get();
		}
	}

	@Autowired
	ApplicationEventPublisher publisher;

	@Autowired
	TransactionTemplate transactionTemplate;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	IdempotencyManager idempotencyManager;

	@Autowired
	IdempotentTestListener listener;

	@Test
	@DisplayName("처리 중 멱등 키가 남은 이벤트는 리스너 본문 없이 발행 기록이 FAILED 로 남는다")
	void leftoverProcessingKey_marksPublicationFailedWithoutInvokingListener() {
		// given
		MemberSignedEvent event = new MemberSignedEvent(9_100_001L, "auth|it", "it");
		String redisKey = KEY_PREFIX + event.getEventId();
		idempotencyManager.attemptLock(redisKey, "crashed-run", 60);

		// when
		transactionTemplate.executeWithoutResult(status -> publisher.publishEvent(event));

		// then
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			List<Map<String, Object>> publications = jdbcTemplate.queryForList(
				"SELECT status, completion_date FROM event_publication WHERE serialized_event LIKE ?",
				"%\"eventId\":\"" + event.getEventId() + "\"%"
			);

			assertThat(publications).hasSize(1);
			assertThat(publications.getFirst().get("STATUS")).isEqualTo("FAILED");
			assertThat(publications.getFirst().get("COMPLETION_DATE")).isNull();
		});

		assertThat(listener.invocations()).isZero();
		assertThat(idempotencyManager.getStoredValue(redisKey).status()).isEqualTo(IdempotencyStatus.PROCESSING);
	}
}
