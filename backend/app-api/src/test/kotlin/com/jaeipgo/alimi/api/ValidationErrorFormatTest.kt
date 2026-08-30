package com.jaeipgo.alimi.api

import com.jaeipgo.alimi.core.RedisTestcontainersConfiguration
import com.jaeipgo.alimi.core.TestcontainersConfiguration
import com.jaeipgo.alimi.api.auth.AuthUser
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

/**
 * 검증 실패 응답의 **모양**을 고정한다.
 *
 * 스프링 기본 400 응답은 다른 에러(404/409)와 형태가 달라서 프론트가 응답을 두 가지로
 * 나눠 다뤄야 한다. `@SmartStoreUrl` 이 이 경로를 타고 나가므로 여기서 계약을 못 박는다.
 */
@Import(TestcontainersConfiguration::class, RedisTestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ValidationErrorFormatTest {

    @Autowired private lateinit var mockMvc: MockMvc

    private val me = AuthUser(userId = 1, email = "user@example.com")

    @Test
    fun `검증 실패는 ProblemDetail 에 필드별 메시지를 담아 400 으로 나간다`() {
        mockMvc.post("/api/notifications") {
            with(oauth2Login().oauth2User(me))
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"recipient":"","channel":"EMAIL","title":"","content":"x"}"""
        }.andExpect {
            status { isBadRequest() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.status") { value(400) }
            jsonPath("$.errors.recipient") { exists() }
            jsonPath("$.errors.title") { exists() }
        }
    }
}
