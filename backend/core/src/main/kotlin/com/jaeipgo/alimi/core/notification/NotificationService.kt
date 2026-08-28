package com.jaeipgo.alimi.core.notification

import com.jaeipgo.alimi.contract.NotificationChannel
import com.jaeipgo.alimi.contract.NotificationCreatedEvent
import com.jaeipgo.alimi.contract.Topics
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class NotificationService(
    private val notificationRepository: NotificationRepository,
    private val kafkaTemplate: KafkaTemplate<String, Any>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun create(command: CreateNotificationCommand): Notification {
        val saved = notificationRepository.save(
            Notification(
                recipient = command.recipient,
                channel = command.channel,
                title = command.title,
                content = command.content,
            ),
        )

        val event = NotificationCreatedEvent(
            notificationId = saved.id!!,
            recipient = saved.recipient,
            channel = saved.channel,
        )
        kafkaTemplate.send(Topics.NOTIFICATION_EVENTS, saved.id.toString(), event)
        log.info("published NotificationCreatedEvent id={}", saved.id)

        return saved
    }

    @Transactional(readOnly = true)
    fun get(id: Long): Notification =
        notificationRepository.findById(id).orElseThrow {
            NoSuchElementException("notification not found: $id")
        }
}

data class CreateNotificationCommand(
    val recipient: String,
    val channel: NotificationChannel,
    val title: String,
    val content: String,
)
