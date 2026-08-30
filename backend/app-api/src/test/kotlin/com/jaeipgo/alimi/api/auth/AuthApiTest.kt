package com.jaeipgo.alimi.api.auth

import com.jaeipgo.alimi.core.RedisTestcontainersConfiguration
import com.jaeipgo.alimi.core.TestcontainersConfiguration
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * 구글 왕복 없이 인증된 요청을 만든다 — `oauth2Login().oauth2User(...)` 가
 * SecurityContext 에 principal 을 직접 심는다.
 */
@Import(TestcontainersConfiguration::class, RedisTestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class AuthApiTest {

    @Autowired private lateinit var mockMvc: MockMvc

    private val me = AuthUser(userId = 42, email = "user@example.com")

    @Nested
    @DisplayName("미인증 요청")
    inner class Unauthenticated {

        @Test
        fun `보호된 API 는 302 가 아니라 401 을 준다`() {
            // 기본 동작(로그인 페이지로 리다이렉트)을 그대로 두면 프론트가 fetch 로 받은
            // HTML 을 JSON 으로 파싱하려다 엉뚱한 곳에서 실패한다.
            mockMvc.get("/api/auth/me")
                .andExpect {
                    status { isUnauthorized() }
                    content { contentTypeCompatibleWith("application/problem+json") }
                }
        }
    }

    @Nested
    @DisplayName("프로브")
    inner class Probes {

        @Test
        fun `actuator health 는 인증 없이 열려 있어야 한다`() {
            // ⚠️ 이게 막히면 liveness/readiness 가 전부 401 을 받아 파드가 계속 죽는다.
            mockMvc.get("/actuator/health").andExpect { status { isOk() } }
        }
    }

    @Nested
    @DisplayName("인증된 요청")
    inner class Authenticated {

        @Test
        fun `me 는 로그인한 계정의 id 와 이메일을 준다`() {
            mockMvc.get("/api/auth/me") { with(oauth2Login().oauth2User(me)) }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.id") { value(42) }
                    jsonPath("$.email") { value("user@example.com") }
                }
        }
    }
}
