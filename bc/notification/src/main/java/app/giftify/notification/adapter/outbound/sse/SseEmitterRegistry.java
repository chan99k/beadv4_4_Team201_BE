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

		emitter.onCompletion(() -> discard(memberId, emitter, "completed"));
		emitter.onTimeout(() -> discard(memberId, emitter, "timed out"));
		emitter.onError(ex -> discard(memberId, emitter, "error: " + ex.getMessage()));

		emitters.put(memberId, emitter);
		return emitter;
	}

	/**
	 * 컨테이너가 이미 종료 처리한 연결을 레지스트리에서만 떼어낸다.
	 *
	 * <p>lifecycle 콜백이 부르는 경로다. 이 시점에 emitter 는 컨테이너가 이미 완료시켰으므로
	 * {@code complete()} 를 다시 부르지 않는다. 옛 연결의 콜백이 재접속 이후 늦게 도착할 수
	 * 있으므로, 현재 등록된 것이 그 emitter 일 때만 제거한다.
	 */
	private void discard(Long memberId, SseEmitter emitter, String reason) {
		log.debug("SSE connection {}: memberId={}", reason, memberId);
		emitters.remove(memberId, emitter);
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
