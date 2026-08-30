package com.jaeipgo.alimi.core.product

import com.jaeipgo.alimi.core.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class StockObservationServiceTest {

    @Autowired private lateinit var service: StockObservationService
    @Autowired private lateinit var registrationService: ProductRegistrationService
    @Autowired private lateinit var productRepository: ProductRepository

    private val t0: Instant = Instant.parse("2026-08-30T00:00:00Z")

    @BeforeEach
    fun clean() = productRepository.deleteAll()

    private fun registerProduct(): Long =
        registrationService.register(
            SmartStoreUrl.parse("https://smartstore.naver.com/ufodripper/products/13112687319"),
            t0,
        ).product.id!!

    private fun reload(id: Long) = productRepository.findById(id).orElseThrow()

    @Nested
    @DisplayName("관측")
    inner class Observation {

        @Test
        fun `첫 관측이 IN_STOCK 이어도 재입고가 아니다`() {
            val id = registerProduct()

            val restocked = service.recordObservation(id, StockStatus.IN_STOCK, now = t0)

            assertThat(restocked).isFalse()
            assertThat(reload(id).lastStatus).isEqualTo(StockStatus.IN_STOCK)
        }

        @Test
        fun `품절에서 판매중으로 바뀌면 재입고다`() {
            val id = registerProduct()
            service.recordObservation(id, StockStatus.OUT_OF_STOCK, now = t0)

            val restocked = service.recordObservation(id, StockStatus.IN_STOCK, now = t0.plusSeconds(300))

            assertThat(restocked).isTrue()
        }

        @Test
        fun `첫 관측 때 상품명을 채운다`() {
            // 알림 본문에 들어갈 값이다.
            val id = registerProduct()

            service.recordObservation(id, StockStatus.OUT_OF_STOCK, name = "테스트 상품", now = t0)

            assertThat(reload(id).name).isEqualTo("테스트 상품")
        }
    }

    @Nested
    @DisplayName("판정 실패")
    inner class Failure {

        @Test
        fun `실패해도 마지막 관측 결과는 남는다`() {
            // 지우면 실패 한 번이 곧바로 재입고 누락이 된다 (§4.1).
            val id = registerProduct()
            service.recordObservation(id, StockStatus.OUT_OF_STOCK, now = t0)

            service.recordFailure(id, t0.plusSeconds(300))

            val product = reload(id)
            assertThat(product.lastStatus).isEqualTo(StockStatus.OUT_OF_STOCK)
            assertThat(product.consecutiveFailures).isEqualTo(1)
        }

        @Test
        fun `실패가 이어지다 관측에 성공하면 재입고를 정상적으로 잡는다`() {
            val id = registerProduct()
            service.recordObservation(id, StockStatus.OUT_OF_STOCK, now = t0)
            repeat(3) { service.recordFailure(id, t0.plusSeconds(300)) }

            val restocked = service.recordObservation(id, StockStatus.IN_STOCK, now = t0.plusSeconds(3600))

            assertThat(restocked).isTrue()
            assertThat(reload(id).consecutiveFailures).isZero()
        }

        @Test
        fun `연속 실패가 쌓이면 감시를 중단한다`() {
            val id = registerProduct()

            val results = (1..Product.SUSPEND_AFTER_FAILURES).map { service.recordFailure(id, t0) }

            assertThat(results.last()).isTrue()
            assertThat(reload(id).monitoringStatus).isEqualTo(MonitoringStatus.SUSPENDED)
        }
    }

    @Nested
    @DisplayName("상품 없음")
    inner class NotFound {

        @Test
        fun `감시를 영구히 끝낸다`() {
            val id = registerProduct()

            service.recordNotFound(id, t0)

            assertThat(reload(id).monitoringStatus).isEqualTo(MonitoringStatus.DELISTED)
        }
    }
}
