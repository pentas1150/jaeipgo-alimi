package com.jaeipgo.alimi.checker

import com.jaeipgo.alimi.checker.check.CheckOutcome
import com.jaeipgo.alimi.checker.check.CheckResult
import com.jaeipgo.alimi.checker.check.CheckerProperties
import com.jaeipgo.alimi.checker.check.PlaywrightSnapshotLoader
import com.jaeipgo.alimi.checker.check.StockChecker
import com.jaeipgo.alimi.checker.check.StockVerdictResolver
import com.jaeipgo.alimi.contract.StockCheckRequested
import com.jaeipgo.alimi.contract.StockRestocked
import com.jaeipgo.alimi.contract.Topics
import com.jaeipgo.alimi.core.TestcontainersConfiguration
import com.jaeipgo.alimi.core.product.MonitoringStatus
import com.jaeipgo.alimi.core.product.Platform
import com.jaeipgo.alimi.core.product.Product
import com.jaeipgo.alimi.core.product.ProductRepository
import com.jaeipgo.alimi.core.product.StockStatus
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue

/**
 * Kafka → 판정 → 상품 행 반영 → 재입고 이벤트까지의 경로를 실제 브로커와 DB 로 검증한다.
 * **Playwright 만 대역으로 둔다** — 네트워크와 브라우저는 이 테스트의 관심사가 아니다.
 */
@Import(
    TestcontainersConfiguration::class,
    StockCheckListenerTest.FakeCheckerConfig::class,
    StockCheckListenerTest.RestockRecorder::class,
)
@SpringBootTest
class StockCheckListenerTest {

    @Autowired private lateinit var kafkaTemplate: KafkaTemplate<String, Any>
    @Autowired private lateinit var productRepository: ProductRepository
    @Autowired private lateinit var checker: FakeStockChecker
    @Autowired private lateinit var recorder: RestockRecorder

    private val t0: Instant = Instant.parse("2026-08-30T00:00:00Z")
    private val productNo = "13112687319"
    private val url = "https://smartstore.naver.com/ufodripper/products/$productNo"

    @BeforeEach
    fun clean() {
        productRepository.deleteAll()
        recorder.received.clear()
    }

    private fun saveProduct(status: StockStatus = StockStatus.UNKNOWN): Long {
        val product = Product.register(Platform.NAVER_SMARTSTORE, "ufodripper", productNo, url, t0)
            .apply { lastStatus = status }
        return productRepository.saveAndFlush(product).id!!
    }

    private fun request(productId: Long) =
        kafkaTemplate.send(
            Topics.STOCK_CHECK_REQUESTED,
            productId.toString(),
            StockCheckRequested(productId, productNo, url),
        )

    private fun awaitProduct(id: Long, check: (Product) -> Boolean) =
        await().atMost(Duration.ofSeconds(30)).until {
            productRepository.findById(id).map(check).orElse(false)
        }

    @Nested
    @DisplayName("판정 결과 반영")
    inner class Applying {

        @Test
        fun `품절을 관측하면 상품 상태가 바뀐다`() {
            val id = saveProduct()
            checker.next = CheckResult(CheckOutcome.OUT_OF_STOCK, "productStatusType=OUTOFSTOCK")

            request(id)

            awaitProduct(id) { it.lastStatus == StockStatus.OUT_OF_STOCK }
        }

        @Test
        fun `URL 이 아니라 이벤트의 상품번호로 대조한다`() {
            // 파싱은 등록 시점 한 곳에서만 한다 (§7.2). 체커는 실려 온 값을 그대로 쓴다.
            val id = saveProduct()
            checker.next = CheckResult(CheckOutcome.OUT_OF_STOCK, "ok")

            request(id)

            awaitProduct(id) { it.lastStatus == StockStatus.OUT_OF_STOCK }
            assertThat(checker.lastExpectedProductNo).isEqualTo(productNo.toLong())
            assertThat(checker.lastUrl).isEqualTo(url)
        }

        @Test
        fun `상품이 사라졌으면 감시를 끝낸다`() {
            val id = saveProduct()
            checker.next = CheckResult(CheckOutcome.NOT_FOUND, "HTTP 404")

            request(id)

            awaitProduct(id) { it.monitoringStatus == MonitoringStatus.DELISTED }
        }
    }

    @Nested
    @DisplayName("재입고")
    inner class Restock {

        @Test
        fun `품절에서 판매중으로 바뀌면 이벤트가 나간다`() {
            val id = saveProduct(status = StockStatus.OUT_OF_STOCK)
            checker.next = CheckResult(CheckOutcome.IN_STOCK, "productStatusType=SALE", productName = "테스트 상품")

            request(id)

            val event = recorder.received.poll(30, java.util.concurrent.TimeUnit.SECONDS)
            assertThat(event).isNotNull()
            assertThat(event!!.key).isEqualTo(id.toString())
            assertThat(event.payload.productId).isEqualTo(id)
            assertThat(event.payload.previousStatus).isEqualTo("OUT_OF_STOCK")
        }

        @Test
        fun `첫 관측이 판매중이면 이벤트가 나가지 않는다`() {
            // 등록 시점에 재고가 있으면 알리지 않는다. 기록만 하고 품절을 기다린다 (규칙 1).
            val id = saveProduct(status = StockStatus.UNKNOWN)
            checker.next = CheckResult(CheckOutcome.IN_STOCK, "productStatusType=SALE")

            request(id)

            awaitProduct(id) { it.lastStatus == StockStatus.IN_STOCK }
            assertThat(recorder.received.poll(3, java.util.concurrent.TimeUnit.SECONDS)).isNull()
        }
    }

    @Nested
    @DisplayName("실패와 차단은 다르게 센다")
    inner class FailureVsBlocked {

        @Test
        fun `판정 실패는 실패로 세고 마지막 관측은 남긴다`() {
            val id = saveProduct(status = StockStatus.OUT_OF_STOCK)
            checker.next = CheckResult(CheckOutcome.UNKNOWN, "모르는 productStatusType")

            request(id)

            awaitProduct(id) { it.consecutiveFailures == 1 }
            // §4.1 — 실패가 관측된 사실을 지우면 다음 재입고를 놓친다.
            assertThat(productRepository.findById(id).orElseThrow().lastStatus)
                .isEqualTo(StockStatus.OUT_OF_STOCK)
        }

        @Test
        fun `차단은 실패로 세지 않고 물러나기만 한다`() {
            // 차단은 전 상품에 동시에 걸린다. 실패로 세면 한 번에 감시 목록 전체가
            // SUSPENDED 로 내려가고, 정작 복구되면 아무도 감시하고 있지 않다.
            val id = saveProduct(status = StockStatus.OUT_OF_STOCK)
            checker.next = CheckResult(CheckOutcome.BLOCKED, "HTTP 490")

            request(id)

            awaitProduct(id) { it.nextCheckAt.isAfter(t0.plusSeconds(600)) }
            val product = productRepository.findById(id).orElseThrow()
            assertThat(product.consecutiveFailures).isZero()
            assertThat(product.monitoringStatus).isEqualTo(MonitoringStatus.ACTIVE)
            assertThat(product.lastStatus).isEqualTo(StockStatus.OUT_OF_STOCK)
        }
    }

    /**
     * Playwright 를 타지 않는 대역. `@Service` 는 kotlin-spring 플러그인이 open 으로
     * 열어주므로 상속이 가능하다 — 별도의 포트를 만들지 않아도 된다.
     */
    class FakeStockChecker(
        loader: PlaywrightSnapshotLoader,
        resolver: StockVerdictResolver,
        properties: CheckerProperties,
    ) : StockChecker(loader, resolver, properties) {

        @Volatile var next: CheckResult = CheckResult.of(CheckOutcome.UNKNOWN, "테스트 기본값")
        @Volatile var lastUrl: String? = null
        @Volatile var lastExpectedProductNo: Long? = null

        override fun check(url: String, expectedProductNo: Long): CheckResult {
            lastUrl = url
            lastExpectedProductNo = expectedProductNo
            return next
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class FakeCheckerConfig {
        @Bean
        @Primary
        fun fakeStockChecker(
            loader: PlaywrightSnapshotLoader,
            resolver: StockVerdictResolver,
            properties: CheckerProperties,
        ) = FakeStockChecker(loader, resolver, properties)
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Component
    class RestockRecorder {

        data class Received(val key: String?, val payload: StockRestocked)

        val received = LinkedBlockingQueue<Received>()

        @KafkaListener(topics = [Topics.STOCK_RESTOCKED], groupId = "test-restock-recorder")
        fun on(
            payload: StockRestocked,
            @org.springframework.messaging.handler.annotation.Header(
                org.springframework.kafka.support.KafkaHeaders.RECEIVED_KEY,
                required = false,
            ) key: String?,
        ) {
            received.add(Received(key, payload))
        }
    }
}
