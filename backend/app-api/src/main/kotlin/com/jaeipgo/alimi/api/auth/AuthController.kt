package com.jaeipgo.alimi.api.auth

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 로그인 상태 조회.
 *
 * 로그인 시작(`/api/oauth2/authorization/google`), 콜백, 로그아웃(`POST /api/auth/logout`)은
 * 전부 Spring Security 필터가 처리한다 — [SecurityConfig] 참고. 여기엔 컨트롤러가 필요 없다.
 */
@RestController
@RequestMapping("/api/auth")
class AuthController {

    /**
     * 미인증이면 401 이다(빈 200 이 아니라).
     * 프론트는 401 을 "로그아웃 상태" 로 읽어 로그인 버튼을 보여주면 된다.
     */
    @GetMapping("/me")
    fun me(@AuthenticationPrincipal me: AuthUser): MeResponse =
        MeResponse(id = me.userId, email = me.email)
}

data class MeResponse(
    val id: Long,
    val email: String,
)
