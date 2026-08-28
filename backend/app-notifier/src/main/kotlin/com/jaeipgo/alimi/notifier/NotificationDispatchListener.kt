package com.jaeipgo.alimi.notifier

import com.jaeipgo.alimi.contract.NotificationDispatch
import com.jaeipgo.alimi.contract.Topics
import com.jaeipgo.alimi.notifier.send.NotificationSenderRegistry
import com.jaeipgo.alimi.notifier.send.RestockNotification
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * `notification.dispatch.v1` 소비 → 채널 어댑터로 위임.
 *
 * 이 클래스는 **어떤 채널이 있는지 모른다.** 이메일도, 디스코드도 모른다.
 * 아는 것은 [NotificationSenderRegistry] 뿐이다.
 *
 * ## 멱등성
 * Kafka 는 at-least-once 라 같은 메시지를 두 번 볼 수 있다.
 * [NotificationDispatchService.claim] 이 `notification_log` UNIQUE 제약으로 걸러낸다.
 *
 * ## 실패 처리
 * 예외를 그대로 던져 Kafka 재시도/DLT 에 맡긴다.
 * 단 영구 실패는 재시도 없이 바로 DLT 로 간다 (KafkaErrorHandlerConfig).
 */
@Component
class NotificationDispatchListener(
    private val registry: NotificationSenderRegistry,
    private val service: NotificationDispatchService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = [Topics.NOTIFICATION_DISPATCH],
        groupId = "\${spring.kafka.consumer.group-id}",
    )
    fun handle(dispatch: NotificationDispatch) {
        val claim = service.claim(dispatch)
        if (claim == null) {
            log.debug("이미 발송된 알림입니다. 건너뜁니다. key={}", dispatch.idempotencyKey)
            return
        }

        try {
            registry.senderFor(dispatch.channel).send(
                RestockNotification(
                    target = dispatch.target,
                    productName = dispatch.productName,
                    productUrl = dispatch.productUrl,
                    detectedAt = dispatch.detectedAt,
                ),
            )
            service.markSent(claim)
        } catch (e: Exception) {
            service.markFailed(claim, e)
            // 던져야 Kafka 재시도/DLT 흐름을 탄다.
            throw e
        }
    }
}
