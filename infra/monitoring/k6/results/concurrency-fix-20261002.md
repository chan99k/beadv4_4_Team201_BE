# 낙관적 락 충돌 처리 개선과 되돌림 (2026-10-01 ~ 10-02)

`concurrency-20261001-233850.md` 에서 발견한 결함을 고치려 한 기록. 수정 두 번 중
하나는 남기고 하나는 되돌렸다. **되돌린 쪽이 이 문서의 요점이다.**

## 출발점

100 VU 가 `fundings` 한 행에 동시 참여할 때 89건이 5xx 로 떨어졌다.

```
org.hibernate.StaleObjectStateException
  Unexpected row count (expected row count 1 but was 0)
  [update g7app.fundings set ... current_amount=? ... where id=? and version=?]
```

낙관적 락이 데이터는 지켰다. 문제는 충돌 예외가 번역되지 않고 컨테이너까지 올라가
500 이 된 것이다. 클라이언트는 이것을 서버 고장과 구분할 수 없다.

## 세 구성의 측정

같은 조건: funding 1003, 100 VU x 1 iteration, 매번 참여 기록을 지우고 시작.

```
구성                          성공   5xx     p95       판정
──────────────────────────────────────────────────────────────────
A  수정 전                     11    89%    715ms     충돌이 500 으로 샌다
B  번역만                      11     0%    862ms     채택
C  번역 + 서버 재시도           48    43%   31.27s    되돌림
D  번역만 (되돌린 뒤 재측정)     10     0%    649ms     B 와 같음
```

A -> B 에서 5xx 가 89건에서 0건이 됐다. 로그의 `ObjectOptimisticLockingFailureException`
스택트레이스 89건이 `INFRA_DB_004` 경고 89건으로 바뀌었다.

## 채택: 충돌을 409 로 번역 (변경 B)

```
InfraErrorCode
  OPTIMISTIC_LOCK_CONFLICT(409, "INFRA_DB_004", "...", retryable = true)

OrderExceptionHandler
  @ExceptionHandler(ObjectOptimisticLockingFailureException.class) -> 409
```

충돌은 서버 고장이 아니라 다시 시도하면 되는 실패다. 409 와 재시도 가능 표시를 주면
클라이언트가 그 사실을 알 수 있다.

회귀 테스트: `bc/core/src/test/java/app/giftify/order/adapter/inbound/web/controller/OrderExceptionHandlerTest.java`

## 되돌림: 서버 재시도 (변경 C)

`ParticipateFundingRetrier` 를 만들어 `@Retryable(maxAttempts = 4, backoff 50ms x2)` 로
감쌌다. 재시도는 트랜잭션 바깥에 있어야 해서 별도 빈으로 뒀다.

**성공은 11 -> 48 로 늘었으나 p95 가 862ms -> 31.27s 가 되고 5xx 가 43% 로 돌아왔다.**

```
HikariPool-1 - Connection is not available, request timed out after 30004ms
(total=10, active=10, idle=0, waiting=24)
```

재시도가 커넥션을 더 오래 점유해 풀 10개를 다 쓰고, 대기 24개가 30초 타임아웃에 걸렸다.
**낙관적 락 충돌 하나를 고치려다 커넥션 풀 고갈이라는 더 나쁜 실패 모드를 만들었다.**

31초 기다리다 500 을 받는 것보다 649ms 에 409 를 받는 편이 낫다고 판단해 배선을 끊었다.
클래스와 테스트는 남겼다. 같은 시도를 다시 하기 전에 이 측정을 보라는 뜻이다.

### 왜 재시도가 답이 아닌가

재시도는 경합의 원인을 줄이지 않는다. **같은 경합을 더 많이 반복한다.**
커넥션 풀이 10개인데 100명이 한 행에 몰리면 어떤 재시도 파라미터도 이 구조를 못 이긴다.
파라미터를 조정하는 것은 숫자를 만지는 일이지 해결이 아니다.

## 남은 것 (미실행)

근본 해결 후보 셋. 모두 범위가 커서 별도 과제로 둔다.

```
1  원자적 UPDATE     update fundings set current_amount = current_amount + ? where id = ?
                     읽고-고치고-쓰는 대신 DB 가 한 번에 더한다. 버전 충돌 자체가 사라진다
                     다만 도메인 객체를 거치지 않아 불변식 검사를 어디서 할지 정해야 한다
2  비관적 락          select ... for update. 충돌 대신 대기로 바꾼다
                     처리량은 직렬화되지만 예측 가능해진다
3  큐잉              참여 요청을 큐로 받고 순차 반영. 응답은 접수 확인으로 바꾼다
                     가장 크고, 사용자 경험 설계가 함께 바뀐다
```

1번이 가장 작고 효과가 직접적이라 다음 후보다.

## 이 작업의 시점

```
2026.01 - 02   팀 프로젝트 기간. 3계층 멱등성과 Outbox 구현
2026.03 - 04   팀 과정 종료 후 개인 작업. k6 시나리오, k3s, 벤치마크
2026.10-01/02  이 문서. 7개월 뒤 부하를 걸어 결함을 찾고 고치고 되돌린 기록
```

실행 환경은 로컬 macOS 이고 컨테이너와 앱이 같은 머신에 있다. 수치는 절대 성능이 아니라
세 구성의 상대 비교로만 읽을 것. 각 구성 1회 측정이다.
