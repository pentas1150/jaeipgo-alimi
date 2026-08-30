package com.jaeipgo.alimi.checker

import com.jaeipgo.alimi.contract.StockRestocked
import com.jaeipgo.alimi.contract.Topics
import com.jaeipgo.alimi.core.product.ProductRestocked
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * 재입고 도메인 이벤트를 `stock.restocked.v1` 로 내보낸다.
 *
 * ⚠️ **`AFTER_COMMIT` 이어야 한다** (규칙 7). 상태 변경과 같은 트랜잭션에서 보내면
 * 커밋이 롤백돼도 메시지는 이미 나가 있다 — **일어나지 않은 재입고 알림이 발송된다.**
 * 이 서비스에서 가장 비싼 종류의 버그다. 놓친 재입고는 사용자가 아쉬워하고 끝이지만,
 * 가짜 재입고 알림은 신뢰를 즉시 잃는다.
 *
 * ⚠️ **키를 반드시 넣는다** (규칙 4). 키가 없으면 sticky 파티셔너가 한 파티션에 다
 * 몰아넣어 오토스케일이 무력화된다 (§12.6 ③⑥ 실측).
 */
@Component
class RestockedEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, Any>,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun on(event: ProductRestocked) {
        val payload = StockRestocked(
            productId = event.productId,
            previousStatus = event.previousStatus.name,
            detectedAt = event.detectedAt,
        )

        kafkaTemplate.send(Topics.STOCK_RESTOCKED, event.productId.toString(), payload)
        log.info("재입고 이벤트 발행: productId={} detectedAt={}", event.productId, event.detectedAt)
    }
}
