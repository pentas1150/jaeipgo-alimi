package com.jaeipgo.alimi.notifier.send

import com.jaeipgo.alimi.contract.NotificationChannel
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 채널 → 어댑터 조회.
 *
 * Spring 이 [NotificationSender] 구현체를 전부 모아 넣어주므로,
 * 새 채널을 추가할 때 **이 클래스는 건드리지 않는다.** 어댑터에 @Component 만 붙이면 된다.
 * 그게 이 구조의 요점이다.
 */
@Component
class NotificationSenderRegistry(senders: List<NotificationSender>) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val byChannel: Map<NotificationChannel, NotificationSender>

    init {
        // 채널당 어댑터는 정확히 하나여야 한다.
        // 둘이 등록되면 어느 쪽이 쓰일지 알 수 없으므로 **기동을 실패시킨다.**
        // 런타임에 조용히 잘못 보내는 것보다 못 뜨는 게 낫다.
        val duplicated = senders.groupBy { it.channel }.filterValues { it.size > 1 }
        require(duplicated.isEmpty()) {
            val detail = duplicated.entries.joinToString("; ") { (channel, list) ->
                "$channel -> ${list.map { it.javaClass.simpleName }}"
            }
            "한 채널에 발송 어댑터가 둘 이상 등록되었습니다: $detail"
        }

        byChannel = senders.associateBy { it.channel }

        val missing = NotificationChannel.entries.toSet() - byChannel.keys
        log.info("발송 어댑터 등록됨: {}", byChannel.mapValues { it.value.javaClass.simpleName })
        if (missing.isNotEmpty()) {
            // 기동을 막지는 않는다 — 쓰지도 않는 채널 때문에 앱이 못 뜨면 곤란하다.
            // 실제로 그 채널로 보내려 할 때 UnsupportedChannelException 으로 걸린다.
            log.warn("어댑터가 없는 채널: {} (해당 채널로는 발송할 수 없습니다)", missing)
        }
    }

    fun senderFor(channel: NotificationChannel): NotificationSender =
        byChannel[channel] ?: throw UnsupportedChannelException(channel, byChannel.keys)

    fun supportedChannels(): Set<NotificationChannel> = byChannel.keys
}
