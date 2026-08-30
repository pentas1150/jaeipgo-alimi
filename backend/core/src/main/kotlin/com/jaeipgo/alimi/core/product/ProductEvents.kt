package com.jaeipgo.alimi.core.product

import java.time.Instant

/**
 * 도메인 이벤트. **Kafka 가 아니라 Spring `ApplicationEventPublisher` 로 나간다.**
 *
 * 규칙 7 — 도메인은 Kafka 를 모른다. 발행은 `@TransactionalEventListener(AFTER_COMMIT)`
 * 를 거친다. 이유는 dual-write 방지다: 트랜잭션 안에서 곧바로 Kafka 에 보내면
 * **커밋이 롤백돼도 메시지는 이미 나가 있다.** 그러면 존재하지 않는 상품에 대한
 * 체크 요청이 떠돌고, 컨슈머는 조회 실패로 계속 재시도한다.
 */

/**
 * 이 상품의 재고를 확인해야 한다.
 *
 * 커밋 후에 `stock.check.requested.v1` 로 옮겨진다. 그 사이에 **중복 억제 게이트**가
 * 끼어든다 — 같은 상품을 여러 명이 동시에 등록하면 상품 행은 UNIQUE 가 하나로 만들어주지만
 * 체크 요청은 인원수만큼 나가고, 그러면 체커가 같은 페이지를 N번 긁어 차단을 자초한다.
 */
data class StockCheckRequired(
    val productId: Long,
    val externalProductNo: String,
    val productUrl: String,
)

/**
 * 재입고가 관측됐다. 커밋 후에 `stock.restocked.v1` 로 옮겨진다.
 *
 * 이 이벤트가 만들어지는 전이는 `OUT_OF_STOCK → IN_STOCK` 단 하나다 (규칙 1).
 */
data class ProductRestocked(
    val productId: Long,
    val previousStatus: StockStatus,
    val detectedAt: Instant,
)
