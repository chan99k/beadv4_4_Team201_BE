package app.giftify.order.adapter.inbound.web.controller;

import app.giftify.shared.api.exception.BusinessException;
import app.giftify.shared.api.exception.ErrorCode;
import app.giftify.shared.api.exception.InfraErrorCode;
import app.giftify.shared.api.exception.InfraException;
import app.giftify.shared.api.response.RsData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice(basePackages = "app.giftify.order")
public class OrderExceptionHandler {

    private static final String SERVER_ERROR_MESSAGE = "서버 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.";

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<RsData<Void>> handleBusinessException(BusinessException ex) {
        ErrorCode errorCode = ex.getErrorCode();

        log.warn("errorCode={}, message={}", errorCode.getCode(), ex.getMessage(), ex);

        RsData<Void> body = RsData.fail(errorCode.getCode(), errorCode.getMessage());

        return ResponseEntity.status(errorCode.getStatusCode())
                .body(body);
    }

    @ExceptionHandler(InfraException.class)
    public ResponseEntity<RsData<Void>> handleInfraException(InfraException ex) {
        ErrorCode errorCode = ex.getErrorCode();

        log.error("errorCode={}, message={}", errorCode.getCode(), ex.getMessage(), ex);

        RsData<Void> body = RsData.fail(errorCode.getCode(), SERVER_ERROR_MESSAGE);

        return ResponseEntity.status(errorCode.getStatusCode())
                .body(body);
    }

    /**
     * 낙관적 락 충돌을 409 로 번역한다.
     *
     * 동시 참여 경합에서 fundings 행의 version 충돌이 나면 Hibernate 가
     * StaleObjectStateException 을 던지고 Spring 이 이것으로 감싼다. 번역하지 않으면
     * 컨테이너까지 올라가 500 이 되는데, 클라이언트 입장에서 서버 고장과 구분되지 않아
     * 재시도해도 되는 실패인지 알 수 없다.
     *
     * 데이터 정합성 자체는 낙관적 락이 지켜준다. 여기서 고치는 것은 실패를 알리는 방식이다.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<RsData<Void>> handleOptimisticLockFailure(ObjectOptimisticLockingFailureException ex) {
        InfraErrorCode errorCode = InfraErrorCode.OPTIMISTIC_LOCK_CONFLICT;

        log.warn("errorCode={}, message={}", errorCode.getCode(), ex.getMessage());

        RsData<Void> body = RsData.fail(errorCode.getMessage(), errorCode.getCode());

        return ResponseEntity.status(errorCode.getStatusCode())
                .body(body);
    }
}
