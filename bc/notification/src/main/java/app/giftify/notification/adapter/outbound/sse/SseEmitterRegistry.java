package app.giftify.notification.adapter.outbound.sse;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class SseEmitterRegistry {

	private static final long TIMEOUT = 30 * 60 * 1000L;

	private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

	public SseEmitter create(Long memberId) {
		remove(memberId);

		SseEmitter emitter = new SseEmitter(TIMEOUT);

		emitter.onCompletion(() -> {
			log.debug("SSE connection completed: memberId={}", memberId);
			emitters.remove(memberId);
		});
		emitter.onTimeout(() -> {
			log.debug("SSE connection timed out: memberId={}", memberId);
			emitters.remove(memberId);
		});
		emitter.onError(ex -> {
			log.debug("SSE connection error: memberId={}, error={}", memberId, ex.getMessage());
			emitters.remove(memberId);
		});

		emitters.put(memberId, emitter);
		return emitter;
	}

	public SseEmitter get(Long memberId) {
		return emitters.get(memberId);
	}

	public void remove(Long memberId) {
		SseEmitter existing = emitters.remove(memberId);
		if (existing != null) {
			existing.complete();
		}
	}

	public void remove(Long memberId, SseEmitter emitter) {
		// 조회 후 제거는 그 사이 재접속이 끼어들 수 있어 원자적이지 않다.
		// remove(key, value) 는 현재 값이 일치할 때만 제거하는 것을 한 연산으로 처리한다.
		// value 가 null 이면 false 를 반환하므로 null emitter 도 여기서 함께 걸러진다.
		if (emitters.remove(memberId, emitter)) {
			emitter.complete();
		}
	}
}
