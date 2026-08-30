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

    // ⚠️ 여기 있던 `productNoFrom(url)` 은 지웠다.
    //
    // 정규식이 `/products/(\d+)` 라 `https://evil.com/products/123` 도 통과시킨다.
    // 체커 입장에서는 이미 검증된 URL 만 보므로 안전했지만, 같은 일을 하는 파서가
    // 두 곳에 있으면 언젠가 느슨한 쪽이 신뢰 경계로 새어 들어간다.
    //
    // 파싱은 등록 시점 한 곳(`core/product/SmartStoreUrl`)에서만 하고, 그 결과가
    // `StockCheckRequested.externalProductNo` 로 실려 온다. 체커는 DB 에서 온 값을
    // 그대로 믿으면 된다. (docs/DESIGN.md §7.2)

    /**
     * 상품 URL 에서 그 상품이 속한 **스토어 홈** URL 을 만든다.
     *
     * `https://smartstore.naver.com/ufodripper/products/123` → `https://smartstore.naver.com/ufodripper`
     *
     * 쿠키 워밍업에 쓴다. ⚠️ 스마트스토어 **루트**(`https://smartstore.naver.com`)를 쓰면 안 된다 —
     * 그건 판매자 센터(`sell.smartstore.naver.com`)로 리다이렉트되고 로그인을 요구한다.
     * 거기서 받은 쿠키를 물려주면 상품 페이지 요청이 전부 로그인 페이지로 끌려간다.
     * (그 증상은 HTTP 200 + `title=NAVER 로그인` 으로 나타나 차단처럼 보이지 않는다)
     */
    private val STORE_HOME_PATTERN = Regex("""^(https?://[^/]+/[^/?#]+)""")

    fun storeHomeFrom(url: String): String? =
        STORE_HOME_PATTERN.find(url)?.groupValues?.get(1)
}
