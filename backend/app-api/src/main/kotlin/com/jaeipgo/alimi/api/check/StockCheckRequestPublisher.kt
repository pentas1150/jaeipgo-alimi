package com.jaeipgo.alimi.api.check

import com.jaeipgo.alimi.contract.StockCheckRequested
import com.jaeipgo.alimi.contract.Topics
import com.jaeipgo.alimi.core.product.StockCheckRequired
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * 도메인 이벤트를 Kafka 커맨드로 옮긴다.
 *
 * ⚠️ **`AFTER_COMMIT` 이어야 한다** (규칙 7). 트랜잭션 안에서 곧바로 보내면 커밋이
 * 롤백돼도 메시지는 이미 나가 있다 — 존재하지 않는 상품에 대한 체크 요청이 떠돌고,
 * 컨슈머는 조회 실패로 계속 재시도하다 DLT 로 간다.
 *
 * ⚠️ **키를 반드시 넣는다** (규칙 4). 키가 없으면 sticky 파티셔너가 한 파티션에 다
 * 몰아넣어 오토스케일이 통째로 무력화된다 (§12.6 ③⑥ 실측).
 * 키가 `productId` 인 덕분에 같은 상품의 체크는 항상 같은 파티션 = 같은 컨슈머로 가고,
 * 순서가 보장된다.
 */
@Component
class StockCheckRequestPublisher(
    private val kafkaTemplate: KafkaTemplate<String, Any>,
    private val gate: CheckRequestGate,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun on(event: StockCheckRequired) {
        if (!gate.tryAcquire(event.productId)) return

        val payload = StockCheckRequested(
            productId = event.productId,
            externalProductNo = event.externalProductNo,
            productUrl = event.productUrl,
        )

        try {
            kafkaTemplate.send(Topics.STOCK_CHECK_REQUESTED, event.productId.toString(), payload)
            log.debug("체크 요청 발행: productId={}", event.productId)
        } catch (e: Exception) {
            // 게이트를 잡은 채로 실패하면 TTL 동안 아무도 체크를 요청하지 못한다.
            gate.release(event.productId)
            throw e
        }
    }
}
