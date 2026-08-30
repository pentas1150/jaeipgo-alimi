package com.jaeipgo.alimi.contract

import java.time.Instant

/**
 * `stock.check.requested.v1` 페이로드. 파티션 키는 `productId`.
 *
 * **커맨드다** — "이 상품의 재고를 확인하라". 체커가 소비한다. (docs/DESIGN.md §5)
 *
 * ⚠️ 이 타입은 프로세스 간 계약이다. 필드 추가는 안전하지만
 * 제거/개명은 아직 옛 버전을 돌리는 컨슈머를 깨뜨린다.
 */
data class StockCheckRequested(
    val productId: Long,

    /**
     * 상품번호를 URL 과 **따로** 싣는 이유가 있다.
     *
     * 체커는 페이지의 상태 JSON 에 있는 `id` 가 지금 보고 있는 상품의 것인지 대조해야 한다
     * (SPA 이동 후 남은 이전 상품 데이터나 껍데기 노드를 걸러내는 유일한 방법이다).
     * 그런데 그 값을 URL 에서 다시 뽑으면 파싱이 두 곳에 생기고, 체커 쪽 정규식은
     * 느슨해서 신뢰 경계로 쓸 수 없다.
     *
     * 파싱은 등록 시점 한 곳(`core/product/SmartStoreUrl`)에서만 하고,
     * 그 결과를 여기에 실어 보낸다. 체커는 DB 에서 온 값을 그대로 믿으면 된다.
     */
    val externalProductNo: String,

    /** 정규화된 URL. 체커가 이 주소를 연다. */
    val productUrl: String,

    val requestedAt: Instant = Instant.now(),
)

/**
 * `stock.restocked.v1` 페이로드. 파티션 키는 `productId`.
 *
 * **도메인 이벤트다** — "재입고가 관측됐다". 이미 일어난 사실이라 과거형이다.
 * 팬아웃 컨슈머가 이걸 구독 N건의 `notification.dispatch.v1` 로 펼친다.
 *
 * 이 이벤트가 나가는 전이는 `OUT_OF_STOCK → IN_STOCK` **단 하나뿐이다** (규칙 1).
 */
data class StockRestocked(
    val productId: Long,

    /**
     * 전이 직전의 재고 상태. 지금은 언제나 `OUT_OF_STOCK` 이다 —
     * 다른 전이에서는 이 이벤트가 발행되지 않기 때문이다.
     *
     * 그래도 싣는 이유는 진단이다. 여기에 다른 값이 찍혀 있다면 규칙 1이 깨진 것이고,
     * 그건 로그를 뒤지지 않고 페이로드만 봐도 알 수 있어야 한다.
     *
     * 타입이 enum 이 아니라 String 인 것은 contract 모듈의 의존성이 0이기 때문이다
     * (`core` 의 `StockStatus.name` 이 그대로 들어간다).
     */
    val previousStatus: String,

    /** 재입고를 **관측한** 시각. 알림 멱등키의 재료가 되므로 재처리해도 같은 값이어야 한다. */
    val detectedAt: Instant,
)
