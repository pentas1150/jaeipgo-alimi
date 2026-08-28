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

/**
 * 샘플 도메인 엔티티. 실제 도메인(Product / Watch / NotificationLog)이 정해지면 교체한다.
 * DB 스키마는 Flyway 마이그레이션(core/src/main/resources/db/migration)에서 관리한다.
 */
@Entity
@Table(name = "notification")
class Notification(

    @Column(name = "recipient", nullable = false, length = 255)
    var recipient: String,

    @Column(name = "channel", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    var channel: NotificationChannel,

    @Column(name = "title", nullable = false, length = 255)
    var title: String,

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    var content: String,

    @Column(name = "status", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    var status: NotificationStatus = NotificationStatus.PENDING,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null,
) : BaseTimeEntity()

enum class NotificationStatus {
    PENDING,
    SENT,
    FAILED,
}
