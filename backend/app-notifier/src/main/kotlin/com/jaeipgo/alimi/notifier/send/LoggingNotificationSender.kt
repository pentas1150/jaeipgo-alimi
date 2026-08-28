package com.jaeipgo.alimi.notifier.send

import com.jaeipgo.alimi.contract.NotificationChannel
import org.slf4j.LoggerFactory

/**
 * 실제로 보내지 않고 로그만 남기는 어댑터. **로컬 개발용이다.**
 *
 * SMTP 없이 파이프라인 전체를 돌려보려고 둔다.
 * 운영에서 이게 활성화되면 사용자는 알림을 못 받는데 시스템은 성공했다고 믿는다 —
 * 그래서 기동할 때 WARN 을 크게 남긴다.
 */
class LoggingNotificationSender(
    override val channel: NotificationChannel,
) : NotificationSender {

    private val log = LoggerFactory.getLogger(javaClass)

    init {
        log.warn(
            "⚠️ {} 채널이 로그 전용 어댑터로 동작합니다. 실제 발송은 일어나지 않습니다. " +
                "(운영 환경이라면 alimi.notification.email.transport=smtp 로 바꾸세요)",
            channel,
        )
    }

    override fun send(notification: RestockNotification) {
        log.info(
            "[발송 시뮬레이션] channel={} target={} product={} url={}",
            channel, notification.target, notification.productName, notification.productUrl,
        )
    }
}
