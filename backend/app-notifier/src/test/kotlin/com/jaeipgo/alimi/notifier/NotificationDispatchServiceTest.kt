package com.jaeipgo.alimi.notifier

import com.jaeipgo.alimi.contract.NotificationChannel
import com.jaeipgo.alimi.contract.NotificationDispatch
import com.jaeipgo.alimi.core.TestcontainersConfiguration
import com.jaeipgo.alimi.core.notification.NotificationLogRepository
import com.jaeipgo.alimi.core.notification.NotificationLogStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant

/**
 * 멱등성은 UNIQUE 제약에 기대므로 실제 DB 없이는 검증할 수 없다.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class NotificationDispatchServiceTest {

    @Autowired private lateinit var service: NotificationDispatchService
    @Autowired private lateinit var repository: NotificationLogRepository

    @BeforeEach
    fun clean() = repository.deleteAll()

    private fun dispatch(key: String = "42:1756400000000") = NotificationDispatch(
        watchId = 42,
        productId = 7,
        channel = NotificationChannel.EMAIL,
        target = "user@example.com",
        productName = "테스트 상품",
        productUrl = "https://smartstore.naver.com/x/products/7",
        idempotencyKey = key,
        detectedAt = Instant.parse("2026-08-29T00:00:00Z"),
    )

    @Test
    fun `처음 보는 메시지는 선점에 성공한다`() {
        val claim = service.claim(dispatch())

        assertThat(claim).isNotNull
        assertThat(repository.findByIdempotencyKey("42:1756400000000")?.status)
            .isEqualTo(NotificationLogStatus.PENDING)
    }

    @Test
    fun `이미 발송 완료된 메시지는 건너뛴다`() {
        val first = service.claim(dispatch())!!
        service.markSent(first)

        // 같은 메시지가 다시 배달됨 (Kafka at-least-once)
        assertThat(service.claim(dispatch())).isNull()
    }

    @Test
    fun `발송에 실패한 메시지는 재시도할 수 있어야 한다`() {
        // 이게 이 설계에서 가장 틀리기 쉬운 지점이다.
        // "행이 있으면 건너뛴다"로 만들면 실패한 알림이 영영 안 나간다.
        val first = service.claim(dispatch())!!
        service.markFailed(first, RuntimeException("SMTP 타임아웃"))

        val retry = service.claim(dispatch())

        assertThat(retry).isEqualTo(first)
        assertThat(repository.findByIdempotencyKey("42:1756400000000")!!.attemptCount)
            .isEqualTo(2)
    }

    @Test
    fun `다음 재입고는 키가 달라 별도로 발송된다`() {
        service.markSent(service.claim(dispatch("42:1756400000000"))!!)

        // 같은 watch 라도 detectedAt 이 다르면 다른 사건이다.
        assertThat(service.claim(dispatch("42:1756499999999"))).isNotNull
        assertThat(repository.count()).isEqualTo(2)
    }
}
