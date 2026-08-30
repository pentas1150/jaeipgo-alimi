package com.jaeipgo.alimi.checker.check

import com.fasterxml.jackson.databind.JsonNode

/**
 * 상품 페이지 한 장에서 긁어온 **원재료**. 판정은 여기 없다.
 *
 * 이 타입이 존재하는 이유가 이 모듈 설계의 핵심이다 —
 * 판정을 `PageSnapshot -> CheckResult` 순수 함수로 만들면
 * **네이버를 건드리지 않고 판정 규칙을 테스트할 수 있다.**
 *
 * 그냥 좋은 습관이라서가 아니다. 네이버는 브라우저가 아닌 클라이언트를 전부 차단하므로
 * CI 가 실제 페이지를 여는 건 **애초에 불가능하다.** 이 분리는 선택이 아니라 전제다.
 *
 * 직렬화 가능해야 한다 — 픽스처로 저장했다가 테스트에서 되읽는다.
 */
data class PageSnapshot(
    /** 최종 URL(리다이렉트 후). 상품번호 대조에 쓴다. */
    val url: String,

    /** 응답 상태 코드. 404/429 판별용. 알 수 없으면 null. */
    val httpStatus: Int?,

    /** `<title>`. 차단 에러 페이지를 알아보는 데 쓴다. */
    val title: String?,

    /**
     * `__PRELOADED_STATE__` 를 통째로 담는다. 없으면 null.
     *
     * ⚠️ 이 안에는 판매자 실명 등 개인정보가 들어있다(일부만 마스킹돼 있다).
     * **로그나 픽스처에 통째로 남기지 않는다.** 판정에 필요한 필드만 추려서 쓴다.
     */
    val preloadedState: JsonNode?,

    /** 화면에 보이는 버튼들. JSON 판정의 교차검증용. */
    val buttons: List<ButtonState> = emptyList(),
) {
    data class ButtonState(
        val text: String,
        val disabled: Boolean,
    )
}
