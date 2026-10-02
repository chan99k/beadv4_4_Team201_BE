package app.giftify.facade;

import app.giftify.facade.command.ParticipateFundingCommand;
import app.giftify.facade.vo.PlaceOrderResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

/**
 * 펀딩 참여의 낙관적 락 충돌을 서버가 흡수한다.
 *
 * <p><b>현재 배선되어 있지 않다 (2026-10-01).</b> 측정해 보고 되돌렸다.
 * 코드를 남기는 이유는 같은 시도를 다시 하기 전에 이 측정을 보라는 것이다.
 *
 * <pre>
 * 100 VU 가 fundings 한 행에 동시 참여 (concurrency-test.js)
 *
 *                        성공    5xx     p95
 *   번역만 (현재)          11      0%    862ms
 *   번역 + 이 재시도       48     43%   31.27s
 *
 * 재시도를 켜니 성공은 11 -> 48 로 늘었으나 p95 가 36배가 되고 5xx 가 돌아왔다.
 * 원인은 커넥션 풀 고갈이다.
 *   HikariPool-1 - Connection is not available, request timed out after 30004ms
 *   (total=10, active=10, idle=0, waiting=24)
 * 재시도가 커넥션을 더 오래 점유해 풀 10개를 다 쓰고 대기 24개가 타임아웃에 걸렸다.
 *
 * 31초 기다리다 500 을 받는 것보다 862ms 에 409 를 받는 편이 낫다고 판단해 배선을 끊었다.
 * 재시도는 클라이언트 몫으로 넘긴다 (409 + 재시도 가능 에러코드).
 * </pre>
 *
 * <p>핫 로우 경합은 재시도로 풀 문제가 아니다. 재시도는 경합의 원인을 줄이지 않고
 * 같은 경합을 더 많이 반복한다. 근본 해결 후보는 금액 증가를 원자적 UPDATE 로 바꾸는 것
 * ({@code update fundings set current_amount = current_amount + ?}), 비관적 락, 큐잉이다.
 * 셋 다 범위가 커서 별도 과제로 둔다.
 *
 * <p>측정 원본: {@code infra/monitoring/k6/results/concurrency-*.md}
 *
 * 왜 별도 빈인가:
 * {@link CoreFacade#participateFunding} 은 {@code @Transactional} 이다. 같은 메서드에
 * {@code @Retryable} 을 붙이면 두 프록시의 적용 순서에 기대게 되고, 순서가 뒤집히면
 * 이미 롤백 표시가 된 트랜잭션 안에서 재시도가 돈다. 빈을 나누면 재시도가 트랜잭션
 * 바깥에 있다는 것이 호출 구조로 보장된다.
 *
 * 왜 전체를 다시 하는가:
 * participateFunding 은 주문 생성부터 펀딩 반영까지를 한 트랜잭션으로 묶는다. 충돌이
 * 나면 전체가 롤백되므로 재시도는 깨끗한 상태에서 다시 시작한다. 주문이 중복 생성되지
 * 않는다.
 *
 * 소진 후에는 예외를 그대로 올린다. OrderExceptionHandler 가 409 로 번역한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParticipateFundingRetrier {

    /** 첫 시도를 포함한 총 시도 횟수 */
    public static final int MAX_ATTEMPTS = 4;

    private final CoreFacade coreFacade;

    @Retryable(
            retryFor = ObjectOptimisticLockingFailureException.class,
            maxAttempts = MAX_ATTEMPTS,
            backoff = @Backoff(delay = 50, multiplier = 2.0, random = true)
    )
    public PlaceOrderResult participate(ParticipateFundingCommand command) {
        return coreFacade.participateFunding(command);
    }
}
