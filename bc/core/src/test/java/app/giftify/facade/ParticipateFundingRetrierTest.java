package app.giftify.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

import app.giftify.facade.command.ParticipateFundingCommand;
import app.giftify.facade.vo.PlaceOrderResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 펀딩 참여 경합에서 낙관적 락 충돌을 서버가 흡수하는지 확인한다.
 *
 * 배경: 2026-10-01 concurrency-test.js 100 VU 실행에서 89건이 충돌로 실패했다.
 * 충돌은 다시 시도하면 되는 실패인데 한 번 부딪히면 그대로 포기하고 있었다.
 *
 * 재시도는 트랜잭션 바깥에 있어야 한다. CoreFacade.participateFunding 이 @Transactional
 * 이므로 같은 메서드에 @Retryable 을 붙이면 이미 롤백된 트랜잭션 안에서 재시도가 돌 수 있다.
 * 그래서 별도 빈으로 감싼다.
 */
@SpringBootTest
@ContextConfiguration(classes = {
        ParticipateFundingRetrier.class,
        ParticipateFundingRetrierTest.TestConfig.class
})
class ParticipateFundingRetrierTest {

    @TestConfiguration
    @EnableRetry
    @Import(ParticipateFundingRetrier.class)
    static class TestConfig {
    }

    @Autowired
    private ParticipateFundingRetrier retrier;

    @MockitoBean
    private CoreFacade coreFacade;

    @Test
    @DisplayName("낙관적 락 충돌이 나면 재시도해서 성공시킨다")
    void given_conflictThenSuccess_when_participate_then_retriesAndSucceeds() {
        ParticipateFundingCommand command = Mockito.mock(ParticipateFundingCommand.class);
        PlaceOrderResult expected = new PlaceOrderResult(100L);

        given(coreFacade.participateFunding(any()))
                .willThrow(new ObjectOptimisticLockingFailureException("Funding", 1002L))
                .willThrow(new ObjectOptimisticLockingFailureException("Funding", 1002L))
                .willReturn(expected);

        PlaceOrderResult actual = retrier.participate(command);

        assertThat(actual).isEqualTo(expected);
        verify(coreFacade, times(3)).participateFunding(any());
    }

    @Test
    @DisplayName("재시도를 모두 소진하면 충돌 예외를 그대로 올린다")
    void given_alwaysConflict_when_participate_then_propagatesAfterExhausting() {
        ParticipateFundingCommand command = Mockito.mock(ParticipateFundingCommand.class);

        willThrow(new ObjectOptimisticLockingFailureException("Funding", 1002L))
                .given(coreFacade).participateFunding(any());

        assertThatThrownBy(() -> retrier.participate(command))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // 소진 후 예외가 올라가야 OrderExceptionHandler 가 409 로 번역한다
        verify(coreFacade, times(ParticipateFundingRetrier.MAX_ATTEMPTS)).participateFunding(any());
    }
}
