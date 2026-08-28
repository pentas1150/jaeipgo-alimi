package com.jaeipgo.alimi.core.notification

import org.springframework.data.jpa.repository.JpaRepository

interface NotificationLogRepository : JpaRepository<NotificationLog, Long> {
    fun findByIdempotencyKey(idempotencyKey: String): NotificationLog?
}
