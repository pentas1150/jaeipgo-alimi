package com.jaeipgo.alimi.checker.check

/**
 * 재고 체크 **1회의 결과**. 상품의 상태가 아니다.
 *
 * `SUSPENDED` 가 여기 없는 건 의도적이다 — 그건 "연속 실패가 임계를 넘었다"는
 * 상품의 생애주기 상태지, 체크 한 번의 결과가 아니다. 섞으면 판정기가 이력을 알아야 한다.
 *
 * `BLOCKED` 를 `UNKNOWN` 과 분리한 이유는 더 실질적이다.
 * 네이버가 차단하면 구매 버튼이 없는 에러 페이지가 오는데, 이걸 `UNKNOWN` 으로 처리하면
 * `consecutive_failures` 가 올라간다. 차단은 보통 전 상품에 동시에 걸리므로
 * **차단 한 번에 감시 목록 전체가 SUSPENDED 로 내려간다.** 차단은 셀렉터 깨짐이 아니다.
 *
 * 결과별 후속 처리는 docs/DESIGN.md §6.2 표 참고 (배치 단계에서 사용).
 */
enum class CheckOutcome {
    /** 살 수 있다. `OUT_OF_STOCK` 다음에 오면 재입고다. */
    IN_STOCK,

    /** 품절이다. */
    OUT_OF_STOCK,

    /**
     * 판정하지 못했다. **절대 재입고로 취급하지 않는다** (fail-closed).
     * 페이지 구조가 바뀌었을 가능성이 있으므로 연속되면 상품을 `SUSPENDED` 로 내린다.
     */
    UNKNOWN,

    /**
     * 네이버가 요청을 거부했다. 우리 문제가 아니라 상대 쪽 사정이다.
     * 상품 상태와 실패 카운터를 **건드리지 않고** 백오프만 한다.
     */
    BLOCKED,

    /** 상품이 사라졌다(404). 재시도해도 의미가 없으므로 즉시 감시를 중단한다. */
    NOT_FOUND,
}

/**
 * 판정 결과 + 그렇게 판정한 근거.
 *
 * `reason` 은 로그와 테스트 실패 메시지에 쓴다. 판정이 틀렸을 때
 * "왜 그렇게 봤는지"가 없으면 원인을 찾는 데 오래 걸린다.
 */
data class CheckResult(
    val outcome: CheckOutcome,
    val reason: String,

    /** 알림 메시지에 쓴다. 판정에 성공했을 때만 채워진다. */
    val productName: String? = null,

    /**
     * 관측용으로만 남긴다. **판정에는 쓰지 않는다** —
     * 품절일 때 0인 건 확인했지만 재고가 있을 때 채워지는지는 확인하지 못했다.
     */
    val stockQuantity: Int? = null,
) {
    companion object {
        fun of(outcome: CheckOutcome, reason: String) = CheckResult(outcome, reason)
    }
}
