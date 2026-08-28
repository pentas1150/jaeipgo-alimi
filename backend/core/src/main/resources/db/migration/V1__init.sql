CREATE TABLE notification
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    recipient  VARCHAR(255) NOT NULL,
    channel    VARCHAR(32)  NOT NULL,
    title      VARCHAR(255) NOT NULL,
    content    TEXT         NOT NULL,
    status     VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_notification_recipient (recipient),
    KEY idx_notification_status (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
