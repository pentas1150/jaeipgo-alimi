package com.jaeipgo.alimi.core.user

import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.Locale

@Service
class UserService(
    private val userRepository: UserRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * OAuth 로그인 결과로 회원을 만들거나 갱신한다.
     *
     * 조회 키는 `(provider, providerUserId)` 다 — 이메일이 아니다.
     * 그래야 사용자가 공급자에서 이메일을 바꿔도 같은 계정에 붙는다.
     */
    @Transactional
    fun upsertOAuthUser(command: OAuthUserCommand): User {
        val email = command.normalizedEmail()

        userRepository.findByProviderAndProviderUserId(command.provider, command.providerUserId)
            ?.let { existing ->
                existing.syncProfile(email, command.emailVerified, command.displayName)
                return existing
            }

        // 같은 이메일이 다른 공급자로 이미 가입돼 있으면 uk_users_email 에 걸린다.
        // 공급자가 하나뿐인 지금은 발생하지 않지만, 나중에 카카오를 붙이면 실제로 생긴다.
        // 그때 자동 연결로 갈지 거부로 갈지는 정책 결정이므로, 지금은 **의도를 드러내며 실패**시킨다.
        // (자동 연결은 command.emailVerified 를 반드시 확인해야 한다 — 안 그러면 계정 탈취다)
        userRepository.findByEmail(email)?.let { other ->
            throw EmailAlreadyRegisteredException(email, other.provider)
        }

        return try {
            userRepository.saveAndFlush(
                User(
                    provider = command.provider,
                    providerUserId = command.providerUserId,
                    email = email,
                    emailVerified = command.emailVerified,
                    displayName = command.displayName,
                ),
            )
        } catch (e: DataIntegrityViolationException) {
            // 같은 사용자가 두 탭에서 동시에 최초 로그인하면 여기로 온다.
            // UNIQUE 가 하나만 통과시켰으므로 진 쪽은 이긴 쪽의 행을 읽으면 된다.
            log.debug("동시 최초 로그인 감지, 기존 행을 사용한다: {}", command.providerUserId)
            userRepository.findByProviderAndProviderUserId(command.provider, command.providerUserId)
                ?: throw e
        }
    }

    @Transactional(readOnly = true)
    fun get(id: Long): User =
        userRepository.findById(id).orElseThrow { NoSuchElementException("user not found: $id") }
}

data class OAuthUserCommand(
    val provider: AuthProvider,
    /** 구글의 `sub`. 공급자 안에서 유일하고 변하지 않는다. */
    val providerUserId: String,
    val email: String,
    val emailVerified: Boolean,
    val displayName: String?,
) {
    /**
     * 이메일을 소문자로 맞춘다.
     *
     * `utf8mb4_unicode_ci` 콜레이션이라 UNIQUE 자체는 대소문자를 무시하지만,
     * 저장값을 정규화해두지 않으면 조회하는 쪽마다 그걸 신경 써야 한다.
     */
    fun normalizedEmail(): String = email.trim().lowercase(Locale.ROOT)
}

class EmailAlreadyRegisteredException(
    val email: String,
    val existingProvider: AuthProvider,
) : RuntimeException("이미 다른 방법으로 가입된 이메일입니다: $email ($existingProvider)")
