package com.jaeipgo.alimi.core.product

import java.time.Duration
import java.time.Instant
import kotlin.math.min

/**
 * 다음 체크 시각을 정한다.
 *
 * 순수 함수로 떼어낸 이유는 하나다 — **간격 정책이 이 서비스의 감지 능력을 직접 결정**하는데,
 * 엔티티 안에 숨어 있으면 바꿀 때마다 DB 를 띄워야 검증할 수 있다.
 *
 * ── 왜 고정 5분인가 ──────────────────────────────────────────────
 * 감지 실패에는 두 축이 있고 **서로 상충한다**:
 *
 *   A. 기준선 부재 — 관측 *실패*(차단). 간격을 줄여도 해결되지 않는다.
 *   B. 전이 누락   — 관측 *빈도* 부족. 두 관측 사이의 전이를 못 본다.
 *
 * 간격을 줄이면 크롤링이 늘어 차단이 증가하고 **A 가 악화된다.** B 로 잃는 알림은
 * 대부분 "배치 간격보다 빨리 소진되는 재입고" 라 사용자가 클릭했을 땐 이미 매진이다.
 * 그래서 우선순위는 A 이고, 지금은 고정 간격 + 실패 시 백오프만 둔다.
 * 구독자 수 기반 적응형은 실측이 쌓인 뒤에 판단한다. (docs/DESIGN.md §4.1)
 */
object CheckSchedule {

    /** 기준선을 확보하기 전(= 한 번도 관측 못 함)의 첫 재시도 간격. */
    val BASELINE_RETRY_BASE: Duration = Duration.ofSeconds(30)

    /**
     * 기준선 확보 전 백오프 상한.
     *
     * 정상 간격(5분)을 넘기지 않는다 — 기준선이 없는 동안에는 구독이 PENDING 이라
     * 사용자가 결과를 기다리고 있다. 여기서 더 늘리면 등록 경험만 나빠지고
     * 차단 회피 효과는 크지 않다.
     */
    val BASELINE_RETRY_MAX: Duration = Duration.ofMinutes(5)

    /**
     * 기준선 확보 후 백오프 상한.
     *
     * 여기는 반대다. 이미 감시 중인 상품이 계속 실패한다는 건 차단당하고 있다는
     * 뜻이므로, 같은 주기로 계속 긁으면 차단을 연장시킨다. 넉넉히 물러난다.
     */
    val FAILURE_BACKOFF_MAX: Duration = Duration.ofHours(1)

    /** 성공적으로 관측한 뒤의 다음 체크 시각. */
    fun afterObservation(now: Instant, checkIntervalSec: Int): Instant =
        now.plusSeconds(checkIntervalSec.toLong())

    /**
     * 판정에 실패한 뒤의 다음 체크 시각.
     *
     * @param consecutiveFailures 이번 실패를 **포함한** 연속 실패 횟수 (1부터)
     * @param baselineEstablished 성공한 관측이 한 번이라도 있었는지
     */
    fun afterFailure(
        now: Instant,
        consecutiveFailures: Int,
        checkIntervalSec: Int,
        baselineEstablished: Boolean,
    ): Instant {
        val base = if (baselineEstablished) {
            Duration.ofSeconds(checkIntervalSec.toLong())
        } else {
            BASELINE_RETRY_BASE
        }
        val cap = if (baselineEstablished) FAILURE_BACKOFF_MAX else BASELINE_RETRY_MAX

        return now.plus(backoff(base, consecutiveFailures, cap))
    }

    /**
     * 지수 백오프. `base * 2^(n-1)` 을 [cap] 으로 자른다.
     *
     * 시프트 폭을 30 으로 제한한다 — 연속 실패가 수십 번 쌓인 뒤 `1L shl 60` 같은 값이
     * 나오면 곱셈이 오버플로해 **과거 시각**이 되고, 그러면 백오프가 사라져
     * 차단당한 상품을 무한정 긁게 된다.
     */
    private fun backoff(base: Duration, consecutiveFailures: Int, cap: Duration): Duration {
        val exponent = min((consecutiveFailures - 1).coerceAtLeast(0), MAX_SHIFT)
        val scaled = base.multipliedBy(1L shl exponent)
        return if (scaled > cap) cap else scaled
    }

    private const val MAX_SHIFT = 30
}
