package com.jaeipgo.alimi.core.notification

import org.springframework.data.jpa.repository.JpaRepository

interface NotificationRepository : JpaRepository<Notification, Long>
