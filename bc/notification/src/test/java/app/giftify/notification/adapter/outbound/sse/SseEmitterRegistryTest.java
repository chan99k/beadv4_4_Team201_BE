package app.giftify.notification.adapter.outbound.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class SseEmitterRegistryTest {

	SseEmitterRegistry registry = new SseEmitterRegistry();

	@Nested
	@DisplayName("remove(memberId, emitter)")
	class RemoveWithEmitter {

		@Test
		@DisplayName("지정된 emitter가 현재 등록된 것과 일치하면 제거한다")
		void removesWhenEmitterMatches() {
			Long memberId = 1L;
			SseEmitter emitter = registry.create(memberId);

			registry.remove(memberId, emitter);

			assertThat(registry.get(memberId)).isNull();
		}

		@Test
		@DisplayName("재접속 후 구 emitter로 제거를 시도하면 새 연결은 유지된다")
		void preservesNewConnectionWhenRemovingOldEmitter() {
			Long memberId = 1L;
			SseEmitter oldEmitter = registry.create(memberId);
			SseEmitter newEmitter = registry.create(memberId);

			registry.remove(memberId, oldEmitter);

			assertThat(registry.get(memberId)).isSameAs(newEmitter);
		}

		@Test
		@DisplayName("emitter가 일치하지 않으면 제거하지 않는다")
		void doesNotRemoveWhenEmitterDoesNotMatch() {
			Long memberId = 1L;
			SseEmitter currentEmitter = registry.create(memberId);
			SseEmitter differentEmitter = new SseEmitter();

			registry.remove(memberId, differentEmitter);

			assertThat(registry.get(memberId)).isSameAs(currentEmitter);
		}

		@Test
		@DisplayName("등록되지 않은 memberId에 대한 제거 시도는 조용히 무시한다")
		void ignoresRemoveForNonexistentMember() {
			Long memberId = 999L;
			SseEmitter emitter = new SseEmitter();

			registry.remove(memberId, emitter);

			assertThat(registry.get(memberId)).isNull();
		}

		@Test
		@DisplayName("미등록 memberId에 null emitter를 넘겨도 예외 없이 무시한다")
		void ignoresNullEmitterForNonexistentMember() {
			assertThatCode(() -> registry.remove(999L, null)).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("등록된 연결이 있을 때 null emitter를 넘겨도 그 연결은 유지된다")
		void nullEmitterDoesNotRemoveCurrentConnection() {
			Long memberId = 1L;
			SseEmitter current = registry.create(memberId);

			registry.remove(memberId, null);

			assertThat(registry.get(memberId)).isSameAs(current);
		}
	}

	@Nested
	@DisplayName("remove(memberId)")
	class RemoveWithMemberId {

		@Test
		@DisplayName("현재 등록된 emitter를 무조건 제거한다")
		void removesCurrentEmitter() {
			Long memberId = 1L;
			registry.create(memberId);

			registry.remove(memberId);

			assertThat(registry.get(memberId)).isNull();
		}
	}

	@Nested
	@DisplayName("create")
	class Create {

		@Test
		@DisplayName("기존 연결이 있으면 제거하고 새 emitter를 등록한다")
		void replacesExistingConnection() {
			Long memberId = 1L;
			SseEmitter first = registry.create(memberId);
			SseEmitter second = registry.create(memberId);

			assertThat(registry.get(memberId)).isSameAs(second);
			assertThat(first).isNotSameAs(second);
		}
	}
}
