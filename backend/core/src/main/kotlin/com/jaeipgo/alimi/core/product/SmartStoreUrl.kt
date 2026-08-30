package com.jaeipgo.alimi.core.product

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * 네이버 스마트스토어 상품 URL 파서.
 *
 * ── 왜 `core` 에 있는가 ──────────────────────────────────────────
 * 등록 검증(`app-api`)과 페이지 판정 교차확인(`app-checker`) 양쪽이 상품번호를 필요로 한다.
 * 하지만 **두 쓰임의 계약이 다르다**:
 *
 * - 등록 파서: "이 문자열이 우리가 지원하는 URL 인가?" → **신뢰 경계**. 엄격해야 한다
 * - 체커 추출: "이미 검증돼 저장된 URL 에서 번호를 뽑는다" → 내부 데이터. 느슨해도 안전
 *
 * 그래서 엄격한 쪽만 여기 두고, 체커는 URL 을 다시 파싱하는 대신
 * `StockCheckRequested` 이벤트에 실려 온 `externalProductNo` 를 쓴다.
 * **파싱은 등록 시점 한 곳에서만 일어난다.**
 *
 * 경계는 이렇게 나뉜다 — **URL 구조는 도메인 지식**(여기), **페이지 DOM/JSON 구조는
 * checker 전용**(`SmartStoreFields`).
 *
 * ⚠️ `feat/stock-verdict` 브랜치의 `SmartStoreFields.productNoFrom()` 은 이 파서와 역할이
 * 겹친다. 그 브랜치를 통합할 때 **삭제하고** 이벤트 필드로 대체한다.
 * 거기 정규식은 `/products/(\d+)` 라 `https://evil.com/products/123` 도 통과시키므로,
 * 신뢰 경계에는 절대 쓰면 안 된다.
 */
object SmartStoreUrl {

    /** 데스크톱 정식 호스트. 모바일 URL 도 여기로 정규화한다. */
    const val CANONICAL_HOST = "smartstore.naver.com"

    private const val MOBILE_HOST = "m.smartstore.naver.com"

    private val SUPPORTED_HOSTS = setOf(CANONICAL_HOST, MOBILE_HOST)

    /**
     * 알아보긴 하지만 아직 지원하지 않는 호스트. 안내 메시지를 구분하기 위해서만 쓴다.
     *
     * `brand.naver.com`(브랜드스토어)은 같은 상품번호 체계를 쓰지만 페이지 구조가
     * 검증되지 않았다. 구조가 다르면 판정이 전부 UNKNOWN 으로 떨어져 **조용히 동작하지
     * 않는다.** 그건 등록을 거부하는 것보다 나쁘다.
     *
     * `naver.me`(공유 단축)는 리다이렉트를 따라가야 하는데, 그러려면 app-api 가 외부
     * HTTP 요청을 해야 하고 §7.1 대로 네이버가 비브라우저 클라이언트를 차단한다.
     */
    private val KNOWN_UNSUPPORTED_HOSTS = mapOf(
        "brand.naver.com" to "브랜드스토어는 아직 지원하지 않습니다",
        "m.brand.naver.com" to "브랜드스토어는 아직 지원하지 않습니다",
        "naver.me" to "공유 단축 링크 대신 브라우저 주소창의 URL 을 붙여넣어 주세요",
        "shopping.naver.com" to "가격비교 페이지가 아니라 스마트스토어 상품 페이지 URL 이 필요합니다",
    )

    /**
     * `/{store}/products/{no}`.
     *
     * 스토어 슬러그는 ASCII 로 제한한다. 길이 상한 128 은 `product.store_id` 컬럼과 맞춘 것이다.
     * 상품번호 19자리는 Long 범위 안이다.
     */
    private val PATH_PATTERN =
        Regex("""^/([A-Za-z0-9_-]{2,128})/products/(\d{1,19})/?$""")

    /**
     * 파싱에 성공하면 결과를, 형식이 아니면 `null` 을 돌려준다.
     * 검증기(`@SmartStoreUrl`)처럼 이유가 필요 없는 곳에서 쓴다.
     */
    fun parseOrNull(raw: String?): ParsedProductUrl? =
        try {
            raw?.let { parse(it) }
        } catch (_: IllegalArgumentException) {
            null
        }

    /**
     * 파싱해서 정규화된 결과를 돌려준다.
     *
     * ```
     * 입력  https://m.smartstore.naver.com/ufodripper/products/13112687319?NaPm=ct%3D..&n_media=27758
     * 출력  https://smartstore.naver.com/ufodripper/products/13112687319
     * ```
     *
     * @throws IllegalArgumentException 지원하는 형식이 아닐 때. 메시지는 사용자에게 보여도 되는 문장이다.
     */
    fun parse(raw: String): ParsedProductUrl {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "상품 URL 을 입력해주세요" }

        val uri = toUri(trimmed)

        // ⚠️ 스킴을 먼저 막는다. javascript:/data:/file: 로 시작하는 문자열이
        // 저장돼서 나중에 화면에 링크로 렌더되면 그대로 공격 경로가 된다.
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == null || scheme == "http" || scheme == "https") {
            "http 또는 https 주소만 등록할 수 있습니다"
        }

        // `https://evil.com@smartstore.naver.com/...` 같은 형태. 실제 호스트는 스마트스토어라
        // 정규화하면 사라지지만, 정상적인 붙여넣기에서는 나올 수 없는 모양이라 거부한다.
        require(uri.userInfo == null) { "올바른 상품 URL 이 아닙니다" }

        val host = uri.host?.lowercase(Locale.ROOT)
            ?: throw IllegalArgumentException("올바른 상품 URL 이 아닙니다")

        KNOWN_UNSUPPORTED_HOSTS[host]?.let { throw IllegalArgumentException(it) }
        require(host in SUPPORTED_HOSTS) {
            "네이버 스마트스토어 상품 URL 이 아닙니다 " +
                "(https://$CANONICAL_HOST/{스토어}/products/{번호})"
        }

        // 쿼리스트링(?NaPm=, ?n_media=, ?utm_*)과 프래그먼트는 여기서 통째로 버려진다 —
        // 경로만 보기 때문이다. 유입 추적 파라미터가 붙었다고 다른 상품이 되면 안 된다.
        val match = PATH_PATTERN.matchEntire(uri.path.orEmpty())
            ?: throw IllegalArgumentException(
                "상품 페이지 주소가 아닙니다 " +
                    "(https://$CANONICAL_HOST/{스토어}/products/{번호})",
            )

        val storeId = match.groupValues[1]
        // 숫자로 한 번 돌려 표준형으로 만든다. `/products/007` 과 `/products/7` 이
        // 서로 다른 행이 되면 같은 상품에 알림이 두 번 나간다.
        val productNo = match.groupValues[2].toLong().toString()

        return ParsedProductUrl(
            platform = Platform.NAVER_SMARTSTORE,
            storeId = storeId,
            externalProductNo = productNo,
            normalizedUrl = "https://$CANONICAL_HOST/$storeId/products/$productNo",
        )
    }

    /**
     * 스킴 없이 붙여넣은 경우(`smartstore.naver.com/x/products/1`)를 받아준다.
     *
     * 관대해 보이지만 위험하지 않다 — 호스트 검사는 그대로 통과해야 하기 때문이다.
     * 반대로 거부하면 사용자는 "왜 안 되는지" 모른 채 막힌다.
     */
    private fun toUri(value: String): URI {
        val candidate = if (value.contains("://")) value else "https://$value"
        return try {
            URI(candidate)
        } catch (e: URISyntaxException) {
            throw IllegalArgumentException("올바른 상품 URL 이 아닙니다", e)
        }
    }
}

/**
 * 파싱 결과. [normalizedUrl] 이 `product.product_url` 에 저장되는 값이다.
 */
data class ParsedProductUrl(
    val platform: Platform,
    val storeId: String,
    val externalProductNo: String,
    val normalizedUrl: String,
)
