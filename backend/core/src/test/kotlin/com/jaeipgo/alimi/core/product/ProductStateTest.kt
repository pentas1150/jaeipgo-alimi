package com.jaeipgo.alimi.core.product

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * 상태 전이는 이 서비스의 핵심 규칙이라 DB 없이 순수하게 검증한다.
 * (실제 스키마와의 정합은 [ProductRepositoryTest] 가 `ddl-auto=validate` 로 본다)
 */
class ProductStateTest {

    private val t0: Instant = Instant.parse("2026-08-30T00:00:00Z")

    private fun product(
        status: StockStatus = StockStatus.UNKNOWN,
        monitoring: MonitoringStatus = MonitoringStatus.ACTIVE,
        failures: Int = 0,
    ) = Product.register(
        platform = Platform.NAVER_SMARTSTORE,
        storeId = "ufodripper",
        externalProductNo = "13112687319",
        productUrl = "https://smartstore.naver.com/ufodripper/products/13112687319",
        now = t0,
    ).apply {
        lastStatus = status
        monitoringStatus = monitoring
        consecutiveFailures = failures
    }

    @Nested
    @DisplayName("알림이 나가는 전이는 하나뿐이다")
    inner class RestockDetection {

        @Test
        fun `OUT_OF_STOCK 에서 IN_STOCK 을 보면 재입고다`() {
            val product = product(status = StockStatus.OUT_OF_STOCK)

            val restocked = product.applyObservation(StockStatus.IN_STOCK, t0)

            assertThat(restocked).isTrue()
            assertThat(product.lastStatus).isEqualTo(StockStatus.IN_STOCK)
        }

        @Test
        fun `UNKNOWN 에서 IN_STOCK 은 재입고가 아니다`() {
            // 직전에 진짜 품절이었는지 확신할 수 없다. 기록만 하고 넘어간다.
            val product = product(status = StockStatus.UNKNOWN)

            val restocked = product.applyObservation(StockStatus.IN_STOCK, t0)

            assertThat(restocked).isFalse()
            assertThat(product.lastStatus).isEqualTo(StockStatus.IN_STOCK)
        }

        @Test
        fun `IN_STOCK 을 두 번 봐도 재입고가 아니다`() {
            // "IN_STOCK 을 관측했다"는 이유로 알리면 안 된다 (규칙 1).
            val product = product(status = StockStatus.IN_STOCK)

            assertThat(product.applyObservation(StockStatus.IN_STOCK, t0)).isFalse()
        }

        @Test
        fun `품절되는 전이는 알림 대상이 아니다`() {
            val product = product(status = StockStatus.IN_STOCK)

            assertThat(product.applyObservation(StockStatus.OUT_OF_STOCK, t0)).isFalse()
            assertThat(product.lastStatus).isEqualTo(StockStatus.OUT_OF_STOCK)
        }

        @Test
        fun `UNKNOWN 은 관측 결과로 넣을 수 없다`() {
            assertThatThrownBy { product().applyObservation(StockStatus.UNKNOWN, t0) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Nested
    @DisplayName("판정 실패는 관측된 사실을 지우지 않는다")
    inner class FailureKeepsBaseline {

        @Test
        fun `실패해도 last_status 는 그대로다`() {
            val product = product(status = StockStatus.OUT_OF_STOCK)

            product.recordCheckFailure(t0)

            assertThat(product.lastStatus).isEqualTo(StockStatus.OUT_OF_STOCK)
            assertThat(product.consecutiveFailures).isEqualTo(1)
        }

        @Test
        fun `실패 뒤에 재입고를 보면 정상적으로 알림이 나간다`() {
            // 이게 핵심이다. 실패 시 UNKNOWN 으로 덮으면 여기서 알림을 놓친다 —
            // 실패 한 번이 곧바로 재입고 누락이 된다.
            val product = product(status = StockStatus.OUT_OF_STOCK)

            product.recordCheckFailure(t0)
            val restocked = product.applyObservation(StockStatus.IN_STOCK, t0.plusSeconds(300))

            assertThat(restocked).isTrue()
        }

        @Test
        fun `실패는 마지막 체크 시도 시각만 갱신한다`() {
            // last_checked_at = 마지막 '시도', last_status = 마지막 '성공한 관측'
            val product = product(status = StockStatus.IN_STOCK)

            product.recordCheckFailure(t0)

            assertThat(product.lastCheckedAt).isEqualTo(t0)
            assertThat(product.lastStatus).isEqualTo(StockStatus.IN_STOCK)
        }
    }

    @Nested
    @DisplayName("감시 생명주기")
    inner class Lifecycle {

        @Test
        fun `연속 실패가 임계값에 이르면 감시를 중단한다`() {
            val product = product(status = StockStatus.OUT_OF_STOCK)

            val suspended = (1..Product.SUSPEND_AFTER_FAILURES)
                .map { product.recordCheckFailure(t0) }

            assertThat(suspended.dropLast(1)).allMatch { !it }
            assertThat(suspended.last()).isTrue()
            assertThat(product.monitoringStatus).isEqualTo(MonitoringStatus.SUSPENDED)
        }

        @Test
        fun `중단돼도 직전 재고 상태는 살아남는다`() {
            // 상태를 한 컬럼에 뒀다면 SUSPENDED 가 OUT_OF_STOCK 을 덮어써서
            // 재개할 때 UNKNOWN 부터 시작했을 것이다. 축을 나눈 이유가 이것이다.
            val product = product(status = StockStatus.OUT_OF_STOCK)

            repeat(Product.SUSPEND_AFTER_FAILURES) { product.recordCheckFailure(t0) }

            assertThat(product.monitoringStatus).isEqualTo(MonitoringStatus.SUSPENDED)
            assertThat(product.lastStatus).isEqualTo(StockStatus.OUT_OF_STOCK)
        }

        @Test
        fun `중단됐다가 재개된 직후의 재입고도 잡는다`() {
            val product = product(status = StockStatus.OUT_OF_STOCK)
            repeat(Product.SUSPEND_AFTER_FAILURES) { product.recordCheckFailure(t0) }

            val restocked = product.applyObservation(StockStatus.IN_STOCK, t0.plusSeconds(3600))

            assertThat(restocked).isTrue()
            assertThat(product.monitoringStatus).isEqualTo(MonitoringStatus.ACTIVE)
            assertThat(product.consecutiveFailures).isZero()
        }

        @Test
        fun `상품이 사라지면 영구히 감시를 끝낸다`() {
            val product = product(status = StockStatus.OUT_OF_STOCK)

            product.delist(t0)

            assertThat(product.monitoringStatus).isEqualTo(MonitoringStatus.DELISTED)
            // 관측된 사실은 여기서도 지우지 않는다.
            assertThat(product.lastStatus).isEqualTo(StockStatus.OUT_OF_STOCK)
        }

        @Test
        fun `이미 중단된 상품은 실패해도 다시 중단 신호를 내지 않는다`() {
            val product = product(status = StockStatus.OUT_OF_STOCK)
            repeat(Product.SUSPEND_AFTER_FAILURES) { product.recordCheckFailure(t0) }

            assertThat(product.recordCheckFailure(t0)).isFalse()
        }
    }

    @Nested
    @DisplayName("차단은 실패와 다르게 센다")
    inner class Blocked {

        @Test
        fun `실패 카운터를 올리지 않는다`() {
            // 차단은 우리 셀렉터가 깨진 게 아니라 상대 쪽 사정이고, 보통 전 상품에
            // 동시에 걸린다. 실패로 세면 차단 한 번에 감시 목록 전체가 SUSPENDED 로
            // 내려가고, 정작 복구되면 아무도 감시하고 있지 않게 된다.
            val product = product(status = StockStatus.OUT_OF_STOCK)

            repeat(10) { product.recordBlocked(t0) }

            assertThat(product.consecutiveFailures).isZero()
            assertThat(product.monitoringStatus).isEqualTo(MonitoringStatus.ACTIVE)
        }

        @Test
        fun `관측된 사실도 지우지 않는다`() {
            val product = product(status = StockStatus.OUT_OF_STOCK)

            product.recordBlocked(t0)

            assertThat(product.lastStatus).isEqualTo(StockStatus.OUT_OF_STOCK)
        }

        @Test
        fun `물러나되 지터를 섞는다`() {
            // 고정 시간만 물러나면 전 상품이 같은 순간에 돌아와 다시 차단당한다.
            val product = product(status = StockStatus.OUT_OF_STOCK)

            product.recordBlocked(t0)

            val backoff = Duration.between(t0, product.nextCheckAt)
            assertThat(backoff).isBetween(
                CheckSchedule.BLOCKED_BACKOFF,
                CheckSchedule.BLOCKED_BACKOFF.plus(CheckSchedule.BLOCKED_JITTER),
            )
        }

        @Test
        fun `실패 백오프보다 오래 물러난다`() {
            val blocked = product(status = StockStatus.OUT_OF_STOCK).apply { recordBlocked(t0) }
            val failed = product(status = StockStatus.OUT_OF_STOCK).apply { recordCheckFailure(t0) }

            assertThat(blocked.nextCheckAt).isAfter(failed.nextCheckAt)
        }
    }

    @Nested
    @DisplayName("기준선")
    inner class Baseline {

        @Test
        fun `등록 직후에는 기준선이 없다`() {
            val product = product()

            assertThat(product.hasBaseline()).isFalse()
            // 다음 배치가 곧바로 집어가야 한다 — 기준선이 없으면 구독이 PENDING 에 머문다.
            assertThat(product.nextCheckAt).isEqualTo(t0)
        }

        @Test
        fun `한 번이라도 관측하면 기준선이 생긴다`() {
            val product = product()

            product.applyObservation(StockStatus.OUT_OF_STOCK, t0)

            assertThat(product.hasBaseline()).isTrue()
        }
    }

    @Nested
    @DisplayName("스토어 슬러그 변경")
    inner class Relocation {

        @Test
        fun `같은 상품번호면 새 행을 만들지 않고 스토어를 옮긴다`() {
            val product = product(status = StockStatus.OUT_OF_STOCK)

            product.relocate("ufodripper2", "https://smartstore.naver.com/ufodripper2/products/13112687319")

            assertThat(product.storeId).isEqualTo("ufodripper2")
            // 상태는 유지된다. 새 행을 만들었다면 구독이 갈라져 알림이 두 번 나갔을 것이다.
            assertThat(product.lastStatus).isEqualTo(StockStatus.OUT_OF_STOCK)
        }
    }

    @Nested
    @DisplayName("체크 주기")
    inner class Scheduling {

        @Test
        fun `관측에 성공하면 정상 간격으로 잡는다`() {
            val product = product(status = StockStatus.OUT_OF_STOCK)

            product.applyObservation(StockStatus.IN_STOCK, t0)

            assertThat(product.nextCheckAt)
                .isEqualTo(t0.plusSeconds(Product.DEFAULT_CHECK_INTERVAL_SEC.toLong()))
        }

        @Test
        fun `기준선이 없으면 30초부터 짧게 재시도한다`() {
            // 사용자가 등록 결과를 기다리는 중이다. 여기서 5분을 기다리게 하면 안 된다.
            val product = product(status = StockStatus.UNKNOWN)

            product.recordCheckFailure(t0)

            assertThat(Duration.between(t0, product.nextCheckAt))
                .isEqualTo(CheckSchedule.BASELINE_RETRY_BASE)
        }

        @Test
        fun `실패가 쌓이면 물러난다`() {
            val product = product(status = StockStatus.OUT_OF_STOCK)

            product.recordCheckFailure(t0)
            val first = Duration.between(t0, product.nextCheckAt)
            product.recordCheckFailure(t0)
            val second = Duration.between(t0, product.nextCheckAt)

            assertThat(second).isGreaterThan(first)
        }

        @Test
        fun `백오프에는 상한이 있다`() {
            val product = product(status = StockStatus.OUT_OF_STOCK)

            repeat(50) { product.recordCheckFailure(t0) }

            // ⚠️ 상한이 없으면 시프트가 오버플로해 **과거 시각**이 되고,
            //    그러면 차단당한 상품을 무한정 긁는다.
            assertThat(product.nextCheckAt).isAfter(t0)
            assertThat(Duration.between(t0, product.nextCheckAt))
                .isEqualTo(CheckSchedule.FAILURE_BACKOFF_MAX)
        }

        @Test
        fun `기준선 확보 전 백오프는 정상 간격을 넘지 않는다`() {
            val product = product(status = StockStatus.UNKNOWN)

            repeat(50) { product.recordCheckFailure(t0) }

            assertThat(Duration.between(t0, product.nextCheckAt))
                .isEqualTo(CheckSchedule.BASELINE_RETRY_MAX)
        }
    }
}
