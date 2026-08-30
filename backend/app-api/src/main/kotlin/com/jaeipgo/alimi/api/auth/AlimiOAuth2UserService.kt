package com.jaeipgo.alimi.api.auth

import com.jaeipgo.alimi.core.user.AuthProvider
import com.jaeipgo.alimi.core.user.EmailAlreadyRegisteredException
import com.jaeipgo.alimi.core.user.OAuthUserCommand
import com.jaeipgo.alimi.core.user.UserService
import org.slf4j.LoggerFactory
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Service

/**
 * 공급자가 준 프로필을 우리 `users` 행으로 옮기고, principal 을 [AuthUser] 로 바꿔 끼운다.
 *
 * **여기가 유일한 공급자 의존 지점이다.** 카카오/네이버를 추가할 때 바뀌는 곳은
 * [toCommand] 의 분기 하나이고, 컨트롤러와 [SecurityConfig] 의 나머지는 그대로다.
 */
@Service
class AlimiOAuth2UserService(
    private val userService: UserService,
) : OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 공급자의 userinfo 엔드포인트 호출은 표준 구현에 맡긴다. */
    private val delegate = DefaultOAuth2UserService()

    override fun loadUser(request: OAuth2UserRequest): OAuth2User {
        val oauth2User = delegate.loadUser(request)
        val registrationId = request.clientRegistration.registrationId

        val command = try {
            toCommand(registrationId, oauth2User.attributes)
        } catch (e: IllegalArgumentException) {
            throw authenticationException("invalid_user_info", e.message, e)
        }

        val user = try {
            userService.upsertOAuthUser(command)
        } catch (e: EmailAlreadyRegisteredException) {
            // 공급자를 늘리기 전에는 발생하지 않는다. 발생하면 정책을 정해야 한다는 신호다.
            log.warn("이메일 중복으로 로그인 거부: {}", e.message)
            throw authenticationException("email_already_registered", e.message, e)
        }

        if (!user.isActive()) {
            throw authenticationException("account_disabled", "비활성화된 계정입니다")
        }

        return AuthUser(userId = user.id!!, email = user.email)
    }

    /**
     * 공급자 응답 → 도메인 커맨드.
     *
     * 구글의 불변 식별자는 `sub` 다. `email` 은 바뀔 수 있으므로 신원으로 쓰지 않는다.
     */
    private fun toCommand(registrationId: String, attributes: Map<String, Any>): OAuthUserCommand {
        val provider = AuthProvider.fromRegistrationId(registrationId)

        return when (provider) {
            AuthProvider.GOOGLE -> OAuthUserCommand(
                provider = provider,
                providerUserId = attributes.requireString("sub"),
                email = attributes.requireString("email"),
                // 구글이 이 값을 안 줄 수도 있다. 없으면 "검증되지 않음" 으로 본다 —
                // 나중에 계정 연결을 붙일 때 이 값이 안전장치가 되므로 낙관적으로 채우면 안 된다.
                emailVerified = attributes["email_verified"] as? Boolean ?: false,
                displayName = attributes["name"] as? String,
            )
        }
    }

    private fun Map<String, Any>.requireString(key: String): String =
        (this[key] as? String)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("공급자 응답에 '$key' 가 없습니다")

    private fun authenticationException(
        code: String,
        description: String?,
        cause: Throwable? = null,
    ): OAuth2AuthenticationException =
        OAuth2AuthenticationException(OAuth2Error(code, description, null), description, cause)
}
