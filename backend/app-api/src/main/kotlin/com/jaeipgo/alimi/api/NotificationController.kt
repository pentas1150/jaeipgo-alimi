package com.jaeipgo.alimi.api

import com.jaeipgo.alimi.contract.NotificationChannel
import com.jaeipgo.alimi.core.notification.CreateNotificationCommand
import com.jaeipgo.alimi.core.notification.Notification
import com.jaeipgo.alimi.core.notification.NotificationService
import com.jaeipgo.alimi.core.notification.NotificationStatus
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/notifications")
class NotificationController(
    private val notificationService: NotificationService,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateNotificationRequest): NotificationResponse =
        NotificationResponse.from(notificationService.create(request.toCommand()))

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): NotificationResponse =
        NotificationResponse.from(notificationService.get(id))
}

data class CreateNotificationRequest(
    @field:NotBlank val recipient: String,
    @field:NotNull val channel: NotificationChannel,
    @field:NotBlank val title: String,
    @field:NotBlank val content: String,
) {
    fun toCommand() = CreateNotificationCommand(recipient, channel, title, content)
}

data class NotificationResponse(
    val id: Long,
    val recipient: String,
    val channel: NotificationChannel,
    val title: String,
    val content: String,
    val status: NotificationStatus,
    val createdAt: Instant?,
) {
    companion object {
        fun from(n: Notification) = NotificationResponse(
            id = n.id!!,
            recipient = n.recipient,
            channel = n.channel,
            title = n.title,
            content = n.content,
            status = n.status,
            createdAt = n.createdAt,
        )
    }
}
