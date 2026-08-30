package com.jaeipgo.alimi.api.check

import com.jaeipgo.alimi.contract.StockCheckRequested
import com.jaeipgo.alimi.contract.Topics
import com.jaeipgo.alimi.core.RedisTestcontainersConfiguration
import com.jaeipgo.alimi.core.TestcontainersConfiguration
import com.jaeipgo.alimi.core.product.ProductRegistrationService
import com.jaeipgo.alimi.core.product.ProductRepository
import com.jaeipgo.alimi.core.product.SmartStoreUrl
import com.jaeipgo.alimi.core.product.StockCheckRequired
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 등록 → (커밋) → 중복 억제 → Kafka 발행까지의 경로를 실제 브로커로 검증한다.
 */
@Import(
    TestcontainersConfiguration::class,
    RedisTestcontainersConfiguration::class,
    CheckRequestPipelineTest.Recorder::class,
)
@SpringBootTest
class CheckRequestPipelineTest {

    @Autowired private lateinit var registrationService: ProductRegistrationService
    @Autowired private lateinit var productRepository: ProductRepository
    @Autowired private lateinit var events: ApplicationEventPublisher
    @Autowired private lateinit var transactionTemplate: TransactionTemplate
    @Autowired private lateinit var redis: StringRedisTemplate
    @Autowired private lateinit var recorder: Recorder

    private val t0: Instant = Instant.parse("2026-08-30T00:00:00Z")

    @BeforeEach
    fun clean() {
        productRepository.deleteAll()
        redis.connectionFactory?.connection?.use { it.serverCommands().flushAll() }
        recorder.received.clear()
    }

    private fun parsed(productNo: String) =
        SmartStoreUrl.parse("https://smartstore.naver.com/ufodripper/products/$productNo")

    private fun awaitMessage(timeoutSec: Long = 20): Recorder.Received? =
        recorder.received.poll(timeoutSec, TimeUnit.SECONDS)

    @Nested
    @DisplayName("발행")
    inner class Publishing {

        @Test
        fun `등록하면 productId 를 키로 체크 요청이 나간다`() {
            val registration = registrationService.register(parsed("13112687319"), t0)

            val message = awaitMessage()

            assertThat(message).isNotNull()
            // ⚠️ 키가 없으면 sticky 파티셔너가 한 파티션에 다 몰아넣어
            //    오토스케일이 통째로 무력화된다 (규칙 4, §12.6 ③⑥).
            assertThat(message!!.key).isEqualTo(registration.product.id.toString())
            assertThat(message.payload.productId).isEqualTo(registration.product.id)
            assertThat(message.payload.externalProductNo).isEqualTo("13112687319")
            assertThat(message.payload.productUrl)
                .isEqualTo("https://smartstore.naver.com/ufodripper/products/13112687319")
        }
    }

    @Nested
    @DisplayName("AFTER_COMMIT")
    inner class AfterCommit {

        @Test
        fun `롤백된 트랜잭션에서는 메시지가 나가지 않는다`() {
            // 규칙 7 (dual-write 방지). 트랜잭션 안에서 곧바로 보내면 롤백돼도 메시지는
            // 이미 나가 있고, 존재하지 않는 상품에 대한 체크 요청이 떠돈다.
            transactionTemplate.execute { status ->
                events.publishEvent(
                    StockCheckRequired(
                        productId = 999_999,
                        externalProductNo = "999999",
                        productUrl = "https://smartstore.naver.com/x/products/999999",
                    ),
                )
                status.setRollbackOnly()
            }

            assertThat(awaitMessage(timeoutSec = 5)).isNull()
        }
    }

    @Nested
    @DisplayName("크롤링 중복 억제")
    inner class Deduplication {

        @Test
        fun `같은 상품을 연달아 등록해도 체크 요청은 한 번만 나간다`() {
            // 상품 행은 UNIQUE 가 하나로 만들어주지만 체크 요청은 인원수만큼 나간다.
            // 그대로 두면 체커가 같은 페이지를 N번 긁어 차단을 자초한다 (§7.1).
            registrationService.register(parsed("13112687319"), t0)
            assertThat(awaitMessage()).isNotNull()

            registrationService.register(parsed("13112687319"), t0)
            registrationService.register(parsed("13112687319"), t0)

            assertThat(awaitMessage(timeoutSec = 5)).isNull()
        }

        @Test
        fun `다른 상품은 서로 막지 않는다`() {
            registrationService.register(parsed("13112687319"), t0)
            registrationService.register(parsed("13614118308"), t0)

            val first = awaitMessage()
            val second = awaitMessage()

            assertThat(listOf(first, second)).doesNotContainNull()
            assertThat(setOf(first!!.payload.externalProductNo, second!!.payload.externalProductNo))
                .containsExactlyInAnyOrder("13112687319", "13614118308")
        }
    }

    /**
     * 발행된 메시지를 모은다. 리스너 파라미터 타입으로 역직렬화 타입이 정해지므로
     * (`ByteArrayJsonMessageConverter`) 별도 설정이 필요 없다.
     */
    @TestConfiguration(proxyBeanMethods = false)
    @Component
    class Recorder {

        data class Received(val key: String?, val payload: StockCheckRequested)

        val received = LinkedBlockingQueue<Received>()

        @KafkaListener(
            topics = [Topics.STOCK_CHECK_REQUESTED],
            groupId = "test-check-request-recorder",
        )
        fun on(
            payload: StockCheckRequested,
            @Header(KafkaHeaders.RECEIVED_KEY, required = false) key: String?,
        ) {
            received.add(Received(key, payload))
        }
    }
}
