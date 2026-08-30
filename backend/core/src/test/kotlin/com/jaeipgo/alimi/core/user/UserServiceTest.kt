package com.jaeipgo.alimi.core.user

import com.jaeipgo.alimi.core.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class UserServiceTest {

    @Autowired private lateinit var userService: UserService
    @Autowired private lateinit var userRepository: UserRepository

    @BeforeEach
    fun clean() = userRepository.deleteAll()

    private fun command(
        sub: String = "google-sub-1",
        email: String = "user@example.com",
        emailVerified: Boolean = true,
        displayName: String? = "테스트 사용자",
    ) = OAuthUserCommand(
        provider = AuthProvider.GOOGLE,
        providerUserId = sub,
        email = email,
        emailVerified = emailVerified,
        displayName = displayName,
    )

    @Nested
    @DisplayName("최초 로그인")
    inner class FirstLogin {

        @Test
        fun `회원을 만든다`() {
            val user = userService.upsertOAuthUser(command())

            assertThat(user.id).isNotNull()
            assertThat(user.provider).isEqualTo(AuthProvider.GOOGLE)
            assertThat(user.providerUserId).isEqualTo("google-sub-1")
            assertThat(user.status).isEqualTo(UserStatus.ACTIVE)
        }

        @Test
        fun `이메일을 소문자로 정규화해 저장한다`() {
            val user = userService.upsertOAuthUser(command(email = "  User@Example.COM  "))

            assertThat(user.email).isEqualTo("user@example.com")
        }
    }

    @Nested
    @DisplayName("재로그인")
    inner class Relogin {

        @Test
        fun `같은 sub 로 다시 로그인해도 회원은 하나다`() {
            val first = userService.upsertOAuthUser(command())
            val second = userService.upsertOAuthUser(command())

            assertThat(second.id).isEqualTo(first.id)
            assertThat(userRepository.count()).isEqualTo(1)
        }

        @Test
        fun `구글에서 이메일을 바꾸면 같은 계정에 반영된다`() {
            // 신원은 이메일이 아니라 sub 다. 이메일로 회원을 찾았다면
            // 사용자가 구글에서 주소를 바꾸는 순간 계정을 잃었을 것이다.
            val first = userService.upsertOAuthUser(command(email = "old@example.com"))

            val second = userService.upsertOAuthUser(command(email = "new@example.com"))

            assertThat(second.id).isEqualTo(first.id)
            assertThat(second.email).isEqualTo("new@example.com")
            assertThat(userRepository.count()).isEqualTo(1)
        }

        @Test
        fun `표시 이름과 이메일 검증 여부도 최신값으로 맞춘다`() {
            userService.upsertOAuthUser(command(emailVerified = false, displayName = "옛 이름"))

            val updated = userService.upsertOAuthUser(command(emailVerified = true, displayName = "새 이름"))

            assertThat(updated.emailVerified).isTrue()
            assertThat(updated.displayName).isEqualTo("새 이름")
        }
    }

    @Nested
    @DisplayName("이메일 충돌")
    inner class EmailConflict {

        @Test
        fun `다른 sub 가 같은 이메일로 들어오면 조용히 붙지 않고 실패한다`() {
            // 지금은 공급자가 구글 하나뿐이라 실제로는 일어나지 않는다.
            // 카카오 등을 붙이면 생기며, 그때 계정 연결 정책을 정해야 한다는 신호다.
            // 검증되지 않은 이메일로 자동 연결하면 계정 탈취가 되므로 기본값은 거부다.
            userService.upsertOAuthUser(command(sub = "google-sub-1"))

            assertThatThrownBy { userService.upsertOAuthUser(command(sub = "google-sub-2")) }
                .isInstanceOf(EmailAlreadyRegisteredException::class.java)

            assertThat(userRepository.count()).isEqualTo(1)
        }
    }

    @Nested
    @DisplayName("공급자 매핑")
    inner class ProviderMapping {

        @Test
        fun `registrationId 를 enum 으로 옮긴다`() {
            assertThat(AuthProvider.fromRegistrationId("google")).isEqualTo(AuthProvider.GOOGLE)
        }

        @Test
        fun `모르는 공급자는 거부한다`() {
            assertThatThrownBy { AuthProvider.fromRegistrationId("kakao") }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
