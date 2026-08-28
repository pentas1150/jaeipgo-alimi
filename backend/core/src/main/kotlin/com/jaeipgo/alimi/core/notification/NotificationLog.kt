package com.jaeipgo.alimi.core.notification

import com.jaeipgo.alimi.contract.NotificationChannel
import com.jaeipgo.alimi.core.BaseTimeEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * 발송 이력. `idempotency_key` UNIQUE 로 중복 발송을 물리 차단한다.
 *
 * 상태는 PENDING → SENT 또는 PENDING → FAILED 로만 간다.
 * FAILED 에서 재시도해 SENT 가 될 수 있다 (Kafka 재시도).
 */
@Entity
@Table(name = "notification_log")
class NotificationLog(

    @Column(name = "watch_id", nullable = false)
    var watchId: Long,

    @Column(name = "product_id", nullable = false)
    var productId: Long,

    @Column(name = "channel", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    var channel: NotificationChannel,

    @Column(name = "target", nullable = false, length = 512)
    var target: String,

    @Column(name = "idempotency_key", nullable = false, length = 255, unique = true)
    var idempotencyKey: String,

    @Column(name = "status", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    var status: NotificationLogStatus = NotificationLogStatus.PENDING,

    @Column(name = "error_message", length = 1024)
    var errorMessage: String? = null,

    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0,

    @Column(name = "sent_at")
    var sentAt: Instant? = null,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null,
) : BaseTimeEntity() {

    fun markSent(now: Instant = Instant.now()) {
        status = NotificationLogStatus.SENT
        sentAt = now
        errorMessage = null
    }

    /** 에러 메시지는 컬럼 길이를 넘길 수 있으므로 잘라서 담는다. */
    fun markFailed(reason: String?) {
        status = NotificationLogStatus.FAILED
        errorMessage = reason?.take(1024)
    }

    fun recordAttempt() {
        attemptCount += 1
    }
}

enum class NotificationLogStatus {
    PENDING,
    SENT,
    FAILED,
}
