package com.jaeipgo.alimi.checker.check

/**
 * 스마트스토어 페이지에 의존하는 **모든 문자열을 여기 모은다.**
 *
 * 네이버가 마크업이나 응답 스키마를 바꾸면 고칠 곳이 여기 하나여야 한다.
 * 판정 로직(`StockVerdictResolver`)에는 리터럴을 두지 않는다.
 *
 * ⚠️ CSS 클래스 셀렉터는 일부러 하나도 없다.
 * 네이버 프론트의 클래스명은 `_2LWuGdF1jw` 같은 해시라 프론트 배포마다 바뀐다.
 * 상태 JSON 의 키와 화면에 보이는 한국어 문구만 쓴다 — 둘 다 훨씬 오래 간다.
 */
object SmartStoreFields {

    /** 페이지에 박혀 있는 SSR 상태 객체의 전역변수 이름. */
    const val STATE_VARIABLE = "__PRELOADED_STATE__"

    /**
     * 진짜 상품 데이터가 있는 노드.
     *
     * ⚠️ 같은 상태 객체 안에 `product` 노드도 있는데 **그건 껍데기다.**
     * Redux 초기값이라 하이드레이션 전에는 전부 null 이고,
     * 하필 `soldout` 이 `false` 로 초기화되어 있어서
     * **품절 상품을 "품절 아님"으로 읽게 된다.** 절대 그쪽을 보지 않는다.
     */
    const val PRODUCT_NODE = "simpleProductForDetailPage"

    const val FIELD_ID = "id"
    const val FIELD_STATUS_TYPE = "productStatusType"
    const val FIELD_STOCK_QUANTITY = "stockQuantity"
    const val FIELD_NAME = "name"

    /** 살 수 있는 상태. **화이트리스트다** — 아래 두 값 외에는 전부 UNKNOWN 으로 떨어뜨린다. */
    const val STATUS_ON_SALE = "SALE"

    /** 품절. */
    const val STATUS_OUT_OF_STOCK = "OUTOFSTOCK"

    /**
     * 차단당했을 때 오는 에러 페이지의 표식.
     *
     * HTTP 429 와 함께 오지만 상태 코드만 믿지 않는다 —
     * 프록시를 타면 상태 코드가 바뀔 수 있고, 본문은 그대로인 경우가 있다.
     */
    val BLOCK_PAGE_MARKERS = listOf("시스템오류", "에러페이지")

    /**
     * "살 수 있다"를 뜻하는 버튼 문구. 교차검증에만 쓴다.
     *
     * `장바구니` 는 **넣지 않았다.** 품절 상품에서도 살아있고(`enableCart: true` 실측),
     * 차단 에러 페이지의 상단 메뉴에도 그 글자가 있다. 신호가 아니라 잡음이다.
     */
    val BUY_BUTTON_TEXTS = listOf("구매하기", "바로구매")

    /** "살 수 없다"를 뜻하는 버튼 문구. 교차검증에만 쓴다. */
    val SOLD_OUT_BUTTON_TEXTS = listOf("품절", "일시품절", "판매중지", "판매종료")

    /**
     * 상품 URL 에서 상품번호를 뽑는다.
     *
     * 이 값으로 상태 JSON 의 `id` 가 **지금 보고 있는 상품의 것인지** 검증한다.
     * 껍데기 노드나 SPA 이동 후 남은 이전 상품 데이터를 걸러내는 유일한 방법이다.
     *
     * 쿼리스트링(`?NaPm=...`)은 정규식이 경로만 보므로 자동으로 무시된다.
     */
    private val PRODUCT_NO_PATTERN = Regex("""/products/(\d+)""")

    fun productNoFrom(url: String): Long? =
        PRODUCT_NO_PATTERN.find(url)?.groupValues?.get(1)?.toLongOrNull()
}
