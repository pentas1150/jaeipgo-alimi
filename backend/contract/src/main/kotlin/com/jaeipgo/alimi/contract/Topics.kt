package com.jaeipgo.alimi.contract

/**
 * Kafka 토픽 이름. 설계는 docs/DESIGN.md §5 참고.
 *
 * 커맨드("~해라")와 이벤트("~했다")를 구분한다:
 *  - STOCK_CHECK_REQUESTED  커맨드. 수신자가 정해져 있다.
 *  - STOCK_RESTOCKED        이벤트(사실). 누가 구독하든 상관없다.
 *  - NOTIFICATION_DISPATCH  커맨드. 수신자가 특정된다.
 *
 * 파티션 키는 전부 순서 보장이 필요한 단위로 잡는다 (productId / watchId).
 */
object Topics {
    const val STOCK_CHECK_REQUESTED = "stock.check.requested.v1"
    const val STOCK_RESTOCKED = "stock.restocked.v1"
    const val NOTIFICATION_DISPATCH = "notification.dispatch.v1"

    /** Dead Letter Topic. 재시도가 소진된 메시지가 쌓인다. */
    fun dlt(topic: String): String = "$topic.dlt"

    /** 배선 확인용 샘플. 실제 도메인 구현 시 제거. */
    const val NOTIFICATION_EVENTS = "notification-events"
}
