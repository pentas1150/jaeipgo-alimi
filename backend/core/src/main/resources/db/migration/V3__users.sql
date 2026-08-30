-- 회원. 구글 OAuth 로 로그인한다.
--
-- ── 신원은 이메일이 아니라 (provider, provider_user_id) 다 ────────────────
-- 구글 계정의 이메일은 사용자가 바꿀 수 있고, 불변 식별자는 `sub` 다.
-- 이메일을 신원으로 삼으면 사용자가 구글에서 이메일을 바꾸는 순간
-- 계정을 잃거나 남의 계정에 붙는다.
--
-- ── provider 축을 지금 두는 이유 ─────────────────────────────────────────
-- 규칙 10(마이그레이션은 직전 버전 앱과 호환) 때문에 컬럼 추가는 공짜지만
-- DROP/RENAME 은 배포 두 번이다. provider 를 미리 두면 카카오/네이버/자체로그인
-- 추가가 **마이그레이션 없이 행 추가**로 끝난다.
--
-- 반대로 password_hash 는 넣지 않았다. 지금 쓰지 않는 컬럼을 미리 두는 건
-- 손해다 — 필요해지면 그때 추가하면 되고, 추가는 공짜다.
--
-- ── email_verified 는 보안 컬럼이다 ──────────────────────────────────────
-- 나중에 "이미 있는 이메일에 다른 provider 로그인이 들어오면 같은 계정으로 연결"
-- 을 구현할 때, 검증 여부를 보지 않고 이메일만으로 연결하면 계정 탈취가 된다.
--
-- 테이블명이 users 인 이유: user 는 MySQL 예약어라 쓸 수 없다.
CREATE TABLE users
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,

    -- GOOGLE (| KAKAO | NAVER | LOCAL ...)
    provider         VARCHAR(32)  NOT NULL,
    -- 구글의 `sub`. provider 안에서 유일하고 변하지 않는다.
    provider_user_id VARCHAR(255) NOT NULL,

    -- 알림 수신 주소이기도 하다. 소문자로 정규화해 저장한다.
    email            VARCHAR(255) NOT NULL,
    email_verified   BOOLEAN      NOT NULL DEFAULT FALSE,
    display_name     VARCHAR(255) NULL,

    -- ACTIVE | DISABLED
    status           VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',

    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_users_provider (provider, provider_user_id),
    UNIQUE KEY uk_users_email (email)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
