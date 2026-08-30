package com.jaeipgo.alimi.api.auth

import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.core.user.OAuth2User
import java.io.Serializable

/**
 * 인증된 사용자. **컨트롤러가 보는 유일한 신원 표현**이다.
 *
 * ```kotlin
 * @PostMapping("/api/watches")
 * fun register(@AuthenticationPrincipal me: AuthUser, ...) = ...
 * ```
 *
 * 컨트롤러는 구글이라는 사실도, `sub` 같은 공급자 개념도 모른다.
 * 카카오를 추가할 때 바뀌는 곳은 [AlimiOAuth2UserService] 하나뿐이고
 * 컨트롤러는 한 줄도 손대지 않는다. (§11 `NotificationSender` 포트와 같은 모양이다)
 *
 * ⚠️ **`Serializable` 이어야 한다.** 세션이 Redis 에 저장되고(`spring-session-data-redis`),
 * 기본 직렬화가 JDK 직렬화다. 이걸 빠뜨리면 로그인 직후 세션을 쓰는 시점에
 * `SerializationException` 이 나는데, 스택이 Spring Session 안쪽이라 원인이 잘 안 보인다.
 *
 * 같은 이유로 [attributes] 에 구글이 준 속성 맵을 통째로 담지 않는다 —
 * 세션마다 Redis 를 불필요하게 채우고, 직렬화 가능성도 공급자 응답에 좌우된다.
 */
class AuthUser(
    val userId: Long,
    val email: String,
) : OAuth2User, Serializable {

    private val attributes: Map<String, Any> = mapOf(
        ATTR_USER_ID to userId,
        ATTR_EMAIL to email,
    )

    /**
     * `OAuth2AuthenticationToken` 의 principal 이름이 된다.
     * 이메일이 아니라 **userId** 다 — 이메일은 바뀔 수 있다.
     */
    override fun getName(): String = userId.toString()

    override fun getAttributes(): Map<String, Any> = attributes

    /**
     * 지금은 역할을 구분하지 않는다. 빈 목록을 주면 `authenticated()` 는 통과하지만
     * 나중에 `hasRole` 을 쓰기 시작할 때 기준점이 없어지므로 하나는 둔다.
     */
    override fun getAuthorities(): Collection<GrantedAuthority> = AUTHORITIES

    override fun equals(other: Any?): Boolean =
        this === other || (other is AuthUser && userId == other.userId)

    override fun hashCode(): Int = userId.hashCode()

    override fun toString(): String = "AuthUser(userId=$userId)"

    companion object {
        private const val serialVersionUID: Long = 1L

        const val ATTR_USER_ID = "userId"
        const val ATTR_EMAIL = "email"

        private val AUTHORITIES: Collection<GrantedAuthority> =
            listOf(SimpleGrantedAuthority("ROLE_USER"))
    }
}
