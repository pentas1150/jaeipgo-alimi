-- 감시 대상 상품. URL 하나당 1행이며 사용자와 무관하다 (구독은 watch 가 담당).
--
-- ── 상태를 두 축으로 나눈 이유 ────────────────────────────────────────────
-- "등록 상태" 한 컬럼에는 성격이 다른 셋이 섞이기 쉽다:
--   ① 재고 상태     — 관측된 사실.        5분마다 바뀐다
--   ② 감시 생명주기 — 우리의 결정.        드물게 바뀐다
--   ③ 등록 진행 상태 — 요청의 속성이다.   상품 행에 자리가 없다 (watch.status 로 간다)
--
-- ①과 ②를 한 컬럼에 넣으면 실제 버그가 난다. OUT_OF_STOCK 상품이 연속 실패로
-- SUSPENDED 가 되는 순간 OUT_OF_STOCK 이 덮어써져 사라지고, 감시를 재개하면
-- UNKNOWN 부터 시작한다. §4.1 상 UNKNOWN → IN_STOCK 은 알리지 않으므로
-- **재입고를 통째로 놓친다.** 이 서비스의 존재 이유가 그 한 번인데도.
--
-- ── UNKNOWN 의 의미 ──────────────────────────────────────────────────────
-- UNKNOWN 은 "지금 상태를 모른다"가 아니라 **"아직 한 번도 관측하지 못했다"** 다.
-- 판정에 실패해도 last_status 는 건드리지 않는다 — 실패는 새로운 사실을 알려주지
-- 않으므로, 마지막으로 '관측된' 사실을 지울 이유가 없다. 지우면 실패 한 번이
-- 곧바로 재입고 누락으로 이어진다. (자세한 근거는 docs/DESIGN.md §4.1)
--
-- 따라서 두 컬럼의 뜻이 다르다:
--   last_checked_at — 마지막 체크 **시도** 시각 (성공/실패 무관). 스케줄링용.
--   last_status     — 마지막 **성공한 관측** 결과. 한 번도 없으면 UNKNOWN.
CREATE TABLE product
(
    id                   BIGINT        NOT NULL AUTO_INCREMENT,

    -- NAVER_SMARTSTORE (| ...)
    platform             VARCHAR(32)   NOT NULL DEFAULT 'NAVER_SMARTSTORE',
    -- smartstore.naver.com/{store_id}
    store_id             VARCHAR(128)  NOT NULL,
    -- /products/{external_product_no}
    external_product_no  VARCHAR(64)   NOT NULL,
    -- 정규화된 URL (쿼리스트링 제거, m. 은 데스크톱으로 통일)
    product_url          VARCHAR(1024) NOT NULL,

    -- 첫 체크 때 채운다. 알림 본문에 들어간다.
    name                 VARCHAR(512)  NULL,
    thumbnail_url        VARCHAR(1024) NULL,

    -- ① 재고 상태: UNKNOWN | IN_STOCK | OUT_OF_STOCK
    last_status          VARCHAR(32)   NOT NULL DEFAULT 'UNKNOWN',
    -- ② 감시 생명주기: ACTIVE | SUSPENDED | DELISTED
    monitoring_status    VARCHAR(32)   NOT NULL DEFAULT 'ACTIVE',

    last_checked_at      DATETIME(6)   NULL,
    -- 배치 선점용 핵심 컬럼. 등록 직후에는 지금 시각이 들어간다(= 즉시 체크 대상).
    next_check_at        DATETIME(6)   NOT NULL,
    check_interval_sec   INT           NOT NULL DEFAULT 300,
    consecutive_failures INT           NOT NULL DEFAULT 0,

    created_at           DATETIME(6)   NOT NULL,
    updated_at           DATETIME(6)   NOT NULL,

    PRIMARY KEY (id),

    -- 중복 등록은 여기서 물리적으로 막힌다. 분산 락이 필요 없다 —
    -- 동시 등록이면 하나는 DataIntegrityViolationException 을 맞고 기존 행을 읽으면 된다.
    UNIQUE KEY uk_product_external (platform, store_id, external_product_no),

    -- ⚠️ 위 UNIQUE 만으로는 부족하다.
    -- 네이버 상품번호는 전역 시퀀스로 보이는데(같은 스토어의 두 상품이 5억 차이),
    -- 그렇다면 판매자가 스토어 슬러그를 바꿨을 때 같은 상품이 두 행이 되어
    -- **알림이 두 번 나간다.** 제약은 보수적으로 두되, 등록 시 이 인덱스로 먼저 조회해
    -- 기존 행을 재사용하고 store_id 를 갱신한다.
    KEY idx_product_no (external_product_no),

    -- 배치 조회용. 재고 상태로 거르지 않는 이유: IN_STOCK 상품도 계속 봐야 한다
    -- (품절돼야 그 다음 재입고를 알릴 수 있다).
    KEY idx_product_due (monitoring_status, next_check_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
