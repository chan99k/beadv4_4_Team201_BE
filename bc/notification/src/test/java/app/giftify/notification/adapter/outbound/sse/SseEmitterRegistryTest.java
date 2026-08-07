package app.giftify.notification.adapter.outbound.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
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

	/**
	 * lifecycle 콜백은 서블릿 컨테이너가 깨운다. bare 단위 테스트에서 {@code emitter.complete()}
	 * 를 부르면 콜백이 발화하지 않으므로, 여기서는 standalone MockMvc 로 비동기 요청을 실제로
	 * 완료시켜 컨테이너가 콜백을 부르게 한다.
	 */
	@Nested
	@DisplayName("컨테이너가 일으키는 제거")
	class ContainerDrivenRemoval {

		@RestController
		static class SubscribeController {

			private final SseEmitterRegistry registry;

			SubscribeController(SseEmitterRegistry registry) {
				this.registry = registry;
			}

			@GetMapping("/subscribe/{memberId}")
			SseEmitter subscribe(@PathVariable Long memberId) {
				return registry.create(memberId);
			}
		}

		MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new SubscribeController(registry)).build();

		@Test
		@DisplayName("재접속 후 구 연결이 완료되어도 새 연결은 레지스트리에 남는다")
		void staleCompletionDoesNotRemoveNewConnection() throws Exception {
			MvcResult stale = mockMvc.perform(get("/subscribe/1")).andReturn();
			SseEmitter newEmitter = registry.create(1L);

			mockMvc.perform(asyncDispatch(stale));

			assertThat(registry.get(1L)).isSameAs(newEmitter);
		}

		@Test
		@DisplayName("재접속하지 않았다면 연결 완료 시 레지스트리에서 제거된다")
		void completionRemovesCurrentConnection() throws Exception {
			MvcResult result = mockMvc.perform(get("/subscribe/1")).andReturn();
			SseEmitter emitter = registry.get(1L);
			assertThat(emitter).isNotNull();

			// 레지스트리를 거치지 않고 연결만 끊는다. 제거는 콜백이 해야 한다.
			emitter.complete();
			mockMvc.perform(asyncDispatch(result));

			assertThat(registry.get(1L)).isNull();
		}

		@Test
		@DisplayName("재접속 후 구 연결이 오류로 끝나도 새 연결은 레지스트리에 남는다")
		void staleErrorDoesNotRemoveNewConnection() throws Exception {
			MvcResult stale = mockMvc.perform(get("/subscribe/1")).andReturn();
			SseEmitter staleEmitter = registry.get(1L);
			SseEmitter newEmitter = registry.create(1L);

			staleEmitter.completeWithError(new RuntimeException("연결 끊김"));
			mockMvc.perform(asyncDispatch(stale));

			assertThat(registry.get(1L)).isSameAs(newEmitter);
		}
	}
}
