package com.jaeipgo.alimi.contract

import java.time.Instant

/**
 * `notification.dispatch.v1` 페이로드. 파티션 키는 `watchId`.
 *
 * 팬아웃 컨슈머가 재입고 1건을 구독 N건으로 펼쳐서 발행하고,
 * 발송 워커가 소비한다. (docs/DESIGN.md §5)
 *
 * ⚠️ 이 타입은 프로세스 간 계약이다. 필드 추가는 안전하지만
 * 제거/개명은 아직 옛 버전을 돌리는 컨슈머를 깨뜨린다.
 */
data class NotificationDispatch(
    val watchId: Long,
    val productId: Long,

    /** 어느 채널로 보낼지. 발송 워커가 이 값으로 어댑터를 고른다. */
    val channel: NotificationChannel,

    /** 채널마다 의미가 다르다: 이메일 주소 / 웹훅 URL / 챗 ID. */
    val target: String,

    val productName: String,
    val productUrl: String,

    /**
     * 중복 발송 차단용. `"{watchId}:{detectedAt.toEpochMilli()}"` 형식.
     *
     * detectedAt 을 넣는 게 핵심이다 — 같은 재입고 사건은 몇 번을 재처리해도 같은 키가 나오고,
     * 다음번 재입고(다른 시각)는 다른 키가 된다.
     */
    val idempotencyKey: String,

    /** 재입고가 관측된 시각. */
    val detectedAt: Instant,
)
