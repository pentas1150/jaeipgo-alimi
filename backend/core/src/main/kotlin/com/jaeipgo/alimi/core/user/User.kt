package com.jaeipgo.alimi.core.user

import com.jaeipgo.alimi.core.BaseTimeEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * 회원.
 *
 * **신원은 `email` 이 아니라 `(provider, providerUserId)` 다.**
 * 구글 계정의 이메일은 사용자가 바꿀 수 있고 불변 식별자는 `sub` 다.
 * 이메일을 신원으로 삼으면 사용자가 이메일을 바꾸는 순간 계정을 잃는다.
 *
 * `email` 은 신원이 아니라 **알림 수신 주소**다. 그래서 로그인할 때마다 갱신한다.
 */
@Entity
@Table(name = "users")
class User(

    @Column(name = "provider", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    var provider: AuthProvider,

    @Column(name = "provider_user_id", nullable = false, length = 255)
    var providerUserId: String,

    @Column(name = "email", nullable = false, length = 255)
    var email: String,

    @Column(name = "email_verified", nullable = false)
    var emailVerified: Boolean = false,

    @Column(name = "display_name", length = 255)
    var displayName: String? = null,

    @Column(name = "status", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    var status: UserStatus = UserStatus.ACTIVE,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null,
) : BaseTimeEntity() {

    /**
     * 로그인할 때마다 공급자가 준 최신 프로필로 맞춘다.
     *
     * 이메일까지 갱신하는 이유: 구독 알림을 **발송 시점의 주소**로 보내기 위해서다.
     * 구독 행에 주소를 스냅샷으로 박아두면 사용자가 이메일을 바꿨을 때 옛 주소로 나간다.
     */
    fun syncProfile(email: String, emailVerified: Boolean, displayName: String?) {
        this.email = email
        this.emailVerified = emailVerified
        this.displayName = displayName
    }

    fun isActive(): Boolean = status == UserStatus.ACTIVE
}

/**
 * 로그인 공급자.
 *
 * 값 추가는 **마이그레이션 없이** 된다 (컬럼은 VARCHAR 다). 카카오/네이버를 붙이거나
 * 자체 로그인(`LOCAL` + `password_hash` 컬럼 추가)으로 갈 때 여기에 한 줄 더하면 된다.
 * 지금 쓰지 않는 값을 미리 늘어놓지 않는 이유는, 죽은 분기를 만들지 않기 위해서다.
 */
enum class AuthProvider {
    GOOGLE,
    ;

    companion object {
        /** Spring Security 의 `registrationId`(예: "google")를 이 enum 으로 옮긴다. */
        fun fromRegistrationId(registrationId: String): AuthProvider =
            entries.firstOrNull { it.name.equals(registrationId, ignoreCase = true) }
                ?: throw IllegalArgumentException("지원하지 않는 로그인 공급자입니다: $registrationId")
    }
}

enum class UserStatus {
    ACTIVE,
    DISABLED,
}
