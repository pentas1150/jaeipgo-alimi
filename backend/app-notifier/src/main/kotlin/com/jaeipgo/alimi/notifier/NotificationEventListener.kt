package com.jaeipgo.alimi.notifier

import com.jaeipgo.alimi.contract.NotificationCreatedEvent
import com.jaeipgo.alimi.contract.Topics
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * 샘플 컨슈머. 실제로는 여기서 이메일/웹훅 발송을 트리거한다.
 *
 * 이 클래스가 notifier 모듈에만 있으므로, 다른 앱은 이 리스너를 아예 갖지 않는다.
 * 예전에는 @Profile 로 껐지만 이제는 클래스패스 자체에 없다.
 */
@Component
class NotificationEventListener {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = [Topics.NOTIFICATION_EVENTS],
        groupId = "\${spring.kafka.consumer.group-id}",
    )
    fun handle(event: NotificationCreatedEvent) {
        log.info("consumed NotificationCreatedEvent: {}", event)
    }
}
