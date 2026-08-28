-- 발송 이력. 멱등성 보장의 핵심 테이블.
--
-- Kafka 는 at-least-once 라 발송 워커가 같은 메시지를 두 번 볼 수 있다.
-- idempotency_key 에 UNIQUE 를 걸어 중복 발송을 DB 레벨에서 물리적으로 차단한다.
--
-- watch / product 테이블은 아직 없으므로 FK 는 걸지 않는다.
-- (도메인 구현 시 추가 여부를 결정한다)
CREATE TABLE notification_log
(
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    watch_id        BIGINT        NOT NULL,
    product_id      BIGINT        NOT NULL,
    channel         VARCHAR(32)   NOT NULL,
    target          VARCHAR(512)  NOT NULL,

    -- "{watchId}:{detectedAtEpochMs}"
    idempotency_key VARCHAR(255)  NOT NULL,

    -- PENDING | SENT | FAILED
    status          VARCHAR(32)   NOT NULL DEFAULT 'PENDING',
    error_message   VARCHAR(1024) NULL,
    attempt_count   INT           NOT NULL DEFAULT 0,
    sent_at         DATETIME(6)   NULL,

    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_log_idem (idempotency_key),
    KEY idx_notification_log_watch (watch_id),
    KEY idx_notification_log_status (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
