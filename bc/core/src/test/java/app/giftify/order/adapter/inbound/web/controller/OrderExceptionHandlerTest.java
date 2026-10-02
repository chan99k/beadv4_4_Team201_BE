package app.giftify.order.adapter.inbound.web.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 동시 참여 경합에서 낙관적 락 충돌이 500 으로 새어 나가던 문제의 회귀 테스트.
 *
 * 배경: 2026-10-01 concurrency-test.js 100 VU 실행에서 89건이 5xx 로 떨어졌다.
 * 원인은 fundings 행의 낙관적 락 충돌이 ObjectOptimisticLockingFailureException 으로
 * 올라오는데 OrderExceptionHandler 가 BusinessException 과 InfraException 만 다뤄
 * 번역되지 않은 채 컨테이너까지 도달한 것이다.
 *
 * 충돌은 서버 고장이 아니라 "다시 시도하면 되는 실패"이므로 409 로 응답해야 한다.
 */
class OrderExceptionHandlerTest {

    private MockMvc mockMvc;

    @RestController
    static class ThrowingController {
        @GetMapping("/test/optimistic-lock")
        public void throwOptimisticLockFailure() {
            throw new ObjectOptimisticLockingFailureException(
                    "Funding", 1002L
            );
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ThrowingController())
                .setControllerAdvice(new OrderExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("낙관적 락 충돌은 500 이 아니라 409 로 응답한다")
    void given_optimisticLockFailure_when_requested_then_respondsConflict() throws Exception {
        mockMvc.perform(get("/test/optimistic-lock"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("낙관적 락 충돌 응답은 재시도 가능한 인프라 에러 코드를 싣는다")
    void given_optimisticLockFailure_when_requested_then_carriesRetryableErrorCode() throws Exception {
        mockMvc.perform(get("/test/optimistic-lock"))
                .andExpect(jsonPath("$.errorCode").value("INFRA_DB_004"));
    }
}
