package com.jaeipgo.alimi.notifier.send

import com.jaeipgo.alimi.contract.NotificationChannel
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.mail.javamail.JavaMailSender
import java.time.ZoneId

/**
 * 어댑터 배선.
 *
 * 채널 하나에 대해 **두 구현 중 정확히 하나만** 뜨도록 `@ConditionalOnProperty` 의
 * 값을 서로 배타적으로 걸었다. 둘 다 뜨면 Registry 가 기동을 실패시키므로
 * 실수하면 조용히 넘어가지 않고 바로 드러난다.
 *
 * 새 채널(디스코드 등)을 추가할 때는 여기 @Bean 을 하나 더 만들거나
 * 어댑터 클래스에 @Component 를 붙이면 된다. Registry 는 건드리지 않는다.
 */
@Configuration
@EnableConfigurationProperties(NotificationProperties::class)
class NotificationSenderConfig {

    @Bean
    @ConditionalOnProperty(name = ["alimi.notification.email.transport"], havingValue = "smtp")
    fun emailNotificationSender(
        mailSender: JavaMailSender,
        properties: NotificationProperties,
    ): NotificationSender = EmailNotificationSender(
        mailSender = mailSender,
        from = properties.email.from,
        zoneId = ZoneId.of(properties.timeZone),
    )

    @Bean
    @ConditionalOnProperty(
        name = ["alimi.notification.email.transport"],
        havingValue = "log",
        matchIfMissing = true,   // 설정을 깜빡했을 때 실제 메일이 나가는 것보다 안전하다
    )
    fun loggingEmailSender(): NotificationSender =
        LoggingNotificationSender(NotificationChannel.EMAIL)
}

@ConfigurationProperties(prefix = "alimi.notification")
data class NotificationProperties(
    val email: Email = Email(),
    /** 알림 본문에 찍히는 시각의 표시 기준. 사용자에게 보이는 값이라 KST 가 기본이다. */
    val timeZone: String = "Asia/Seoul",
) {
    data class Email(
        val transport: Transport = Transport.LOG,
        val from: String = "no-reply@jaeipgo.local",
    )

    enum class Transport { LOG, SMTP }
}
