package com.jaeipgo.alimi.notifier.send

import com.jaeipgo.alimi.contract.NotificationChannel
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * 이 추상화의 값어치는 "채널 추가가 정말 파일 하나로 끝나는가"에 달려 있다.
 * 그걸 테스트로 고정한다. Spring 컨텍스트 없이 도는 순수 단위 테스트다.
 */
class NotificationSenderRegistryTest {

    private class FakeSender(
        override val channel: NotificationChannel,
        private val failWith: NotificationSendException? = null,
    ) : NotificationSender {
        val sent = mutableListOf<RestockNotification>()
        override fun send(notification: RestockNotification) {
            failWith?.let { throw it }
            sent += notification
        }
    }

    private val sample = RestockNotification(
        target = "user@example.com",
        productName = "테스트 상품",
        productUrl = "https://smartstore.naver.com/x/products/1",
        detectedAt = Instant.parse("2026-08-29T00:00:00Z"),
    )

    @Test
    fun `채널에 맞는 어댑터로 위임한다`() {
        val email = FakeSender(NotificationChannel.EMAIL)
        val webhook = FakeSender(NotificationChannel.WEBHOOK)
        val registry = NotificationSenderRegistry(listOf(email, webhook))

        registry.senderFor(NotificationChannel.WEBHOOK).send(sample)

        assertThat(webhook.sent).containsExactly(sample)
        assertThat(email.sent).isEmpty()
    }

    @Test
    fun `새 채널은 어댑터만 추가하면 등록된다 - Registry 는 수정하지 않는다`() {
        val before = NotificationSenderRegistry(listOf(FakeSender(NotificationChannel.EMAIL)))
        assertThat(before.supportedChannels()).containsExactly(NotificationChannel.EMAIL)

        // 어댑터 하나를 리스트에 더한 것 외에는 아무것도 바꾸지 않았다.
        val after = NotificationSenderRegistry(
            listOf(FakeSender(NotificationChannel.EMAIL), FakeSender(NotificationChannel.PUSH)),
        )
        assertThat(after.supportedChannels())
            .containsExactlyInAnyOrder(NotificationChannel.EMAIL, NotificationChannel.PUSH)
    }

    @Test
    fun `한 채널에 어댑터가 둘이면 기동에 실패한다`() {
        assertThatThrownBy {
            NotificationSenderRegistry(
                listOf(FakeSender(NotificationChannel.EMAIL), FakeSender(NotificationChannel.EMAIL)),
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("둘 이상")
            .hasMessageContaining("EMAIL")
    }

    @Test
    fun `어댑터가 없는 채널은 재시도 불가 예외를 던진다`() {
        val registry = NotificationSenderRegistry(listOf(FakeSender(NotificationChannel.EMAIL)))

        val thrown = catchSendException { registry.senderFor(NotificationChannel.SMS) }

        assertThat(thrown).isInstanceOf(UnsupportedChannelException::class.java)
        // 없는 채널은 몇 번 재시도해도 생기지 않는다 → 즉시 DLT 로 가야 한다.
        assertThat(thrown.retryable).isFalse()
    }

    @Test
    fun `일시적 실패와 영구 실패는 재시도 여부가 다르다`() {
        assertThat(TransientSendException("타임아웃").retryable).isTrue()
        assertThat(PermanentSendException("잘못된 주소").retryable).isFalse()
    }

    private fun catchSendException(block: () -> Unit): NotificationSendException =
        runCatching(block).exceptionOrNull() as NotificationSendException
}
