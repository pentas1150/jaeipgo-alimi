package com.jaeipgo.alimi.notifier.send

import com.jaeipgo.alimi.contract.NotificationChannel
import java.time.Instant

/**
 * 알림 발송 포트(port).
 *
 * 채널이 늘어나도 이 인터페이스를 구현하는 어댑터를 하나 추가하는 것으로 끝나야 한다.
 * 호출하는 쪽(NotificationDispatchListener)은 구현체를 절대 알지 않는다.
 *
 * ## 이 인터페이스가 지키는 규칙
 *
 * 1. **채널 고유 개념이 새어나오지 않는다.**
 *    `subject`, `smtpHost`, `webhookUrl` 같은 이름이 여기 있으면 안 된다.
 *    이메일의 제목/본문, 디스코드의 embed 포맷은 각 어댑터가 알아서 만든다.
 *
 * 2. **발송에 필요한 것만 받는다** (ISP).
 *    Kafka DTO(`NotificationDispatch`)를 그대로 넘기지 않는 이유가 이것이다 —
 *    `idempotencyKey`, `watchId` 는 *디스패치* 관심사지 *발송* 관심사가 아니다.
 *    어댑터가 와이어 포맷 변경에 영향받지 않는 효과도 있다.
 *
 * 3. **실패는 예외로 알린다.** 성공/실패를 boolean 으로 돌려주지 않는다.
 *    재시도와 DLT 를 Kafka 에게 맡기는 설계이므로, 던져야 그 흐름을 탄다.
 *    다만 **재시도할 가치가 있는 실패인지 구분해서** 던져야 한다
 *    ([TransientSendException] vs [PermanentSendException]).
 */
interface NotificationSender {

    /**
     * 이 어댑터가 담당하는 채널. 채널당 어댑터는 **정확히 하나**여야 하며,
     * 중복 등록은 [NotificationSenderRegistry] 가 기동 시점에 잡아낸다.
     */
    val channel: NotificationChannel

    /**
     * @throws TransientSendException 재시도하면 성공할 수 있는 실패 (타임아웃, 5xx, 레이트리밋)
     * @throws PermanentSendException 재시도해도 소용없는 실패 (잘못된 주소, 4xx)
     */
    fun send(notification: RestockNotification)
}

/**
 * 발송할 내용. **채널 중립적**이어야 한다.
 *
 * 어댑터는 이걸 받아 자기 채널에 맞게 렌더링한다 —
 * 이메일은 제목+HTML, 디스코드는 embed JSON, SMS 는 짧은 평문.
 * 그래서 여기에 미리 렌더링된 문자열(`title`, `body`)을 담지 않는다.
 * 그렇게 하면 채널별 표현을 통제할 수 없게 된다.
 */
data class RestockNotification(
    /** 채널마다 의미가 다르다: 이메일 주소 / 웹훅 URL / 챗 ID. */
    val target: String,
    val productName: String,
    val productUrl: String,
    val detectedAt: Instant,
)

/**
 * 발송 실패. `retryable` 로 Kafka 재시도 여부가 갈린다.
 *
 * 이 구분이 중요한 이유: 존재하지 않는 이메일 주소를 3번 재시도하는 건 낭비고,
 * 그 사이 뒤에 쌓인 정상 메시지들이 밀린다. 영구 실패는 즉시 DLT 로 보낸다.
 * (배선은 KafkaErrorHandlerConfig)
 */
sealed class NotificationSendException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    abstract val retryable: Boolean
}

/** 재시도하면 성공할 수 있다. 타임아웃, 5xx, 레이트리밋 등. */
class TransientSendException(
    message: String,
    cause: Throwable? = null,
) : NotificationSendException(message, cause) {
    override val retryable = true
}

/** 재시도해도 소용없다. 잘못된 수신 주소, 4xx 등. */
class PermanentSendException(
    message: String,
    cause: Throwable? = null,
) : NotificationSendException(message, cause) {
    override val retryable = false
}

/** 해당 채널을 담당하는 어댑터가 등록돼 있지 않다. 재시도해도 소용없다. */
class UnsupportedChannelException(
    channel: NotificationChannel,
    supported: Set<NotificationChannel>,
) : NotificationSendException(
    "발송 어댑터가 없는 채널입니다: $channel (등록된 채널: $supported)",
) {
    override val retryable = false
}
