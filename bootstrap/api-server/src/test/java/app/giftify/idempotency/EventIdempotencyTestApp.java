package app.giftify.idempotency;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

import app.giftify.security.common.config.SharedSecurityAutoConfiguration;
import app.giftify.support.common.config.RedisConfig;
import giftify.support.web.idempotency.aop.EventIdempotencyAspect;
import giftify.support.web.idempotency.manager.IdempotencyManager;
import giftify.support.web.idempotency.util.PayloadHasher;

@SpringBootApplication(
	exclude = {
		SharedSecurityAutoConfiguration.class
	},
	excludeName = {
		"org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
		"org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration",
		"org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration",
		"org.springframework.boot.batch.autoconfigure.BatchAutoConfiguration",
		"org.springframework.modulith.runtime.autoconfigure.SpringModulithRuntimeAutoConfiguration"
	}
)
@Import({RedisConfig.class, IdempotencyManager.class, PayloadHasher.class, EventIdempotencyAspect.class})
class EventIdempotencyTestApp {
}
