package com.jaeipgo.alimi.core.product

import com.jaeipgo.alimi.core.BaseTimeEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * 감시 대상 상품. URL 하나당 1행이며 **사용자와 무관하다** (구독은 `watch` 가 담당한다).
 *
 * 재고 체크는 상품 단위, 알림은 구독 단위다. 같은 상품을 N명이 구독해도
 * 브라우저는 한 번만 띄운다.
 *
 * ── 상태가 두 축인 이유 ────────────────────────────────────────
 * [lastStatus] 는 **관측된 사실**이고 [monitoringStatus] 는 **우리의 결정**이다.
 * 성격도 변경 빈도도 다르므로 한 컬럼에 넣지 않는다. 넣으면 `SUSPENDED` 가
 * 직전 재고 상태를 덮어써서, 감시를 재개할 때 재입고를 놓친다.
 */
@Entity
@Table(name = "product")
class Product(

    @Column(name = "platform", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    var platform: Platform,

    @Column(name = "store_id", nullable = false, length = 128)
    var storeId: String,

    @Column(name = "external_product_no", nullable = false, length = 64)
    var externalProductNo: String,

    @Column(name = "product_url", nullable = false, length = 1024)
    var productUrl: String,

    /** 배치 선점용. 등록 직후에는 현재 시각이 들어간다 (= 즉시 체크 대상). */
    @Column(name = "next_check_at", nullable = false)
    var nextCheckAt: Instant,

    /** 첫 체크 때 채운다. 알림 본문에 들어간다. */
    @Column(name = "name", length = 512)
    var name: String? = null,

    @Column(name = "thumbnail_url", length = 1024)
    var thumbnailUrl: String? = null,

    /** 마지막으로 **성공한 관측**의 결과. 한 번도 없으면 [StockStatus.UNKNOWN]. */
    @Column(name = "last_status", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    var lastStatus: StockStatus = StockStatus.UNKNOWN,

    @Column(name = "monitoring_status", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    var monitoringStatus: MonitoringStatus = MonitoringStatus.ACTIVE,

    /** 마지막 체크 **시도** 시각. 성공/실패를 가리지 않는다. */
    @Column(name = "last_checked_at")
    var lastCheckedAt: Instant? = null,

    @Column(name = "check_interval_sec", nullable = false)
    var checkIntervalSec: Int = DEFAULT_CHECK_INTERVAL_SEC,

    @Column(name = "consecutive_failures", nullable = false)
    var consecutiveFailures: Int = 0,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null,
) : BaseTimeEntity() {

    /** 성공한 관측이 한 번이라도 있었는가. 없으면 재입고를 판정할 기준선이 없다. */
    fun hasBaseline(): Boolean = lastStatus != StockStatus.UNKNOWN

    /**
     * 새로 등록하려는 사람에게 답하기 위해 **지금 다시 긁어야 하는가.**
     *
     * 이미 최근에 관측했다면 그 결과를 그대로 쓴다. 페이지 요청은 이 서비스에서 가장
     * 귀한 자원이다 — §7.1 대로 네이버가 봇을 차단하므로, 아낄 수 있는 요청은 아껴야
     * 차단 확률이 내려간다. 인기 상품일수록 여러 명이 몰려 등록하는데, 그때마다 긁으면
     * 정확히 가장 나쁜 순간에 가장 많이 긁는 셈이 된다.
     *
     * 기준이 [checkIntervalSec] 인 이유: 그보다 오래된 값은 어차피 다음 배치가 갱신할
     * 예정이었던 값이다. 그 정도로 낡았으면 새로 보는 게 맞다.
     */
    fun needsFreshCheck(now: Instant): Boolean {
        if (!hasBaseline()) return true
        val checkedAt = lastCheckedAt ?: return true
        return !checkedAt.plusSeconds(checkIntervalSec.toLong()).isAfter(now)
    }

    /**
     * 체크 결과를 반영하고 **알림이 필요한 전이인지** 돌려준다.
     *
     * 알림이 나가는 전이는 `OUT_OF_STOCK → IN_STOCK` **단 하나다** (규칙 1).
     * - 등록 시점에 이미 재고가 있으면 알리지 않는다. `IN_STOCK` 으로 기록만 하고 품절을 기다린다.
     * - `UNKNOWN → IN_STOCK` 도 알리지 않는다 — 직전에 진짜 품절이었는지 확신할 수 없다.
     *
     * 관측에 성공했다는 것은 차단이 풀렸다는 뜻이므로 [consecutiveFailures] 를 0으로
     * 되돌리고, 중단됐던 감시도 되살린다. 이때 [lastStatus] 는 그대로 남아 있으므로
     * **중단 전에 품절이었다면 재개 직후의 재입고도 정상적으로 잡힌다.**
     */
    fun applyObservation(observed: StockStatus, now: Instant): Boolean {
        require(observed != StockStatus.UNKNOWN) {
            "판정 실패는 recordCheckFailure() 로 처리한다 — UNKNOWN 을 관측 결과로 쓰지 않는다"
        }

        val restocked = lastStatus == StockStatus.OUT_OF_STOCK && observed == StockStatus.IN_STOCK

        lastStatus = observed
        lastCheckedAt = now
        consecutiveFailures = 0
        if (monitoringStatus == MonitoringStatus.SUSPENDED) {
            monitoringStatus = MonitoringStatus.ACTIVE
        }
        nextCheckAt = CheckSchedule.afterObservation(now, checkIntervalSec)

        return restocked
    }

    /**
     * 판정에 실패했다. 연속 실패가 [suspendAfter] 회에 이르면 감시를 중단하고 `true` 를 돌려준다.
     *
     * ⚠️ **[lastStatus] 를 건드리지 않는다.** 판정 실패는 새로운 사실을 알려주지 않으므로
     * 마지막으로 관측된 사실을 지울 이유가 없다. 지우면 이렇게 된다:
     *
     * ```
     * 09:00  OUT_OF_STOCK 관측
     * 09:05  차단당해 실패 → last_status 를 UNKNOWN 으로 덮으면
     * 09:10  IN_STOCK 관측 → UNKNOWN → IN_STOCK 이라 알림 없음  ← 재입고 누락
     * ```
     *
     * 실패 한 번이 곧바로 재입고 누락이 된다. 규칙 2(fail-closed)는 "판정 실패를 재입고로
     * 취급하지 마라"는 뜻이고, 상태를 바꾸지 않으면 전이가 없으므로 알림도 없다 —
     * fail-closed 는 그대로 지켜진다. 오히려 UNKNOWN 으로 덮는 쪽이 정보를 잃는다.
     */
    fun recordCheckFailure(now: Instant, suspendAfter: Int = SUSPEND_AFTER_FAILURES): Boolean {
        consecutiveFailures += 1
        lastCheckedAt = now
        nextCheckAt = CheckSchedule.afterFailure(
            now = now,
            consecutiveFailures = consecutiveFailures,
            checkIntervalSec = checkIntervalSec,
            baselineEstablished = hasBaseline(),
        )

        if (consecutiveFailures >= suspendAfter && monitoringStatus == MonitoringStatus.ACTIVE) {
            monitoringStatus = MonitoringStatus.SUSPENDED
            return true
        }
        return false
    }

    /**
     * 네이버가 요청을 거부했다 (차단, 비표준 상태 코드, 상대 서버 오류).
     *
     * ⚠️ **[consecutiveFailures] 를 올리지 않는다.** 차단은 우리 셀렉터가 깨진 게 아니라
     * 상대 쪽 사정이고, 보통 한 상품이 아니라 **전 상품에 동시에** 걸린다. 실패로 세면
     * 차단 한 번에 감시 목록 전체가 `SUSPENDED` 로 내려간다 — 정작 복구되면 아무도
     * 감시하고 있지 않은 상태가 된다.
     *
     * [lastStatus] 도 그대로 둔다. 판정 실패와 같은 이유다 (§4.1).
     * 하는 일은 **물러나는 것뿐**이고, 지터를 섞어 전 상품이 같은 순간에 돌아오지 않게 한다.
     */
    fun recordBlocked(now: Instant, nextCheckAt: Instant = CheckSchedule.afterBlocked(now)) {
        lastCheckedAt = now
        this.nextCheckAt = nextCheckAt
    }

    /**
     * 상품 페이지가 사라졌다. 되살아날 일이 사실상 없으므로 감시를 영구히 끝낸다.
     *
     * [lastStatus] 는 여기서도 건드리지 않는다 — 마지막으로 관측된 사실은 사실이다.
     */
    fun delist(now: Instant) {
        monitoringStatus = MonitoringStatus.DELISTED
        lastCheckedAt = now
    }

    /** 첫 체크에서 알아낸 표시 정보를 채운다. */
    fun describe(name: String?, thumbnailUrl: String?) {
        name?.takeIf { it.isNotBlank() }?.let { this.name = it }
        thumbnailUrl?.takeIf { it.isNotBlank() }?.let { this.thumbnailUrl = it }
    }

    /**
     * 판매자가 스토어 슬러그를 바꾼 경우를 흡수한다.
     *
     * 상품번호가 전역 시퀀스라면 `store_id` 가 바뀌어도 같은 상품이다. 새 행을 만들면
     * 구독이 갈라져 알림이 두 번 나간다. (`idx_product_no` 를 둔 이유)
     */
    fun relocate(storeId: String, productUrl: String) {
        this.storeId = storeId
        this.productUrl = productUrl
    }

    companion object {
        const val DEFAULT_CHECK_INTERVAL_SEC = 300

        /**
         * 연속 실패 몇 회에 감시를 중단할지.
         *
         * 기준선 확보 전에는 30초부터 백오프하므로 5회면 대략 8분, 확보 후에는 5분부터라
         * 대략 1시간 반이다. 차단이 일시적일 때 성급히 포기하지 않으면서, 영영 안 되는
         * 상품을 무한정 긁지도 않는 지점으로 잡았다.
         */
        const val SUSPEND_AFTER_FAILURES = 5

        /**
         * 새로 등록되는 상품.
         *
         * `nextCheckAt = now` 이므로 다음 배치가 곧바로 집어간다. 등록 직후에는
         * 기준선을 확보하는 것이 급하다 — 그게 없으면 구독이 `PENDING` 에 머문다.
         */
        fun register(
            platform: Platform,
            storeId: String,
            externalProductNo: String,
            productUrl: String,
            now: Instant = Instant.now(),
        ) = Product(
            platform = platform,
            storeId = storeId,
            externalProductNo = externalProductNo,
            productUrl = productUrl,
            nextCheckAt = now,
        )
    }
}

/**
 * 관측된 재고 상태.
 *
 * [UNKNOWN] 은 "지금 상태를 모른다"가 아니라 **"아직 한 번도 관측하지 못했다"** 다.
 * 판정 실패는 이 값을 바꾸지 않는다 ([Product.recordCheckFailure] 참고).
 */
enum class StockStatus {
    UNKNOWN,
    IN_STOCK,
    OUT_OF_STOCK,
}

/**
 * 감시 생명주기 — 이 상품을 계속 볼 것인가에 대한 우리의 결정.
 *
 * 재고 상태와 성격이 다르므로 컬럼을 나눈다. `watch` 테이블과 헷갈리지 않도록
 * `watchState` 가 아니라 `monitoringStatus` 라는 이름을 쓴다.
 */
enum class MonitoringStatus {
    /** 정상 감시 중. 배치가 집어간다. */
    ACTIVE,

    /** 연속 판정 실패로 잠시 물러난 상태. 다시 관측에 성공하면 자동으로 [ACTIVE] 로 돌아온다. */
    SUSPENDED,

    /** 상품 페이지가 사라졌다. 되살아나지 않는다. */
    DELISTED,
}

enum class Platform {
    NAVER_SMARTSTORE,
}
