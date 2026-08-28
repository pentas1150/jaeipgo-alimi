package com.jaeipgo.alimi.contract

import java.time.Instant

/**
 * Kafka 로 발행되는 이벤트 페이로드(JSON 직렬화).
 *
 * 이 모듈의 타입은 **프로세스 간 계약**이다. 필드를 지우거나 이름을 바꾸면
 * 아직 옛 버전을 돌리고 있는 컨슈머가 깨진다. 추가는 안전하고, 제거는 위험하다.
 */
data class NotificationCreatedEvent(
    val notificationId: Long,
    val recipient: String,
    val channel: NotificationChannel,
    val occurredAt: Instant = Instant.now(),
)

enum class NotificationChannel {
    EMAIL,
    SMS,
    PUSH,
    WEBHOOK,
}
