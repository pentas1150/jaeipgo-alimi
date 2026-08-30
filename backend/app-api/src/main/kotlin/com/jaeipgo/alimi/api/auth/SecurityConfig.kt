package com.jaeipgo.alimi.api.auth

import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler

/**
 * `app-api` 의 인증/인가.
 *
 * ── ⚠️ OAuth 경로를 `/api/` 아래로 옮긴 이유 ────────────────────────────
 * `frontend/nginx.conf` 는 `location /api/` 만 백엔드로 프록시하고 나머지는
 * SPA fallback(`try_files ... /index.html`)이다. Spring Security 의 기본 경로
 * (`/oauth2/authorization/google`, `/login/oauth2/code/google`)는 `/api/` **밖**이라
 * 그대로 두면 **nginx 가 index.html 을 돌려주고 OAuth 가 시작조차 못 한다.**
 * 콜백도 마찬가지라 구글이 돌려준 `code` 가 백엔드에 도달하지 못한다.
 * `vite.config.js` 의 dev 프록시와 `k8s/overlays/pi/ingress.yaml` 도 같은 구조다.
 *
 * 그래서 nginx 를 고치는 대신 Security 경로를 `/api/` 아래로 내렸다.
 * DESIGN §10.3 대로 이 프로젝트는 "프론트와 API 가 같은 오리진" 이라는 불변식으로
 * CORS 설정을 통째로 없앴는데, 경로를 추가로 뚫으면 nginx + Ingress + vite
 * **세 곳**을 고쳐야 하고 그 불변식이 흐려진다. 이렇게 하면 인프라를 한 줄도 안 건드린다.
 *
 * 구글 콘솔에 등록할 리다이렉트 URI: `https://<도메인>/api/login/oauth2/code/google`
 */
@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val oAuth2UserService: AlimiOAuth2UserService,
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .authorizeHttpRequests { auth ->
                auth
                    // ⚠️ 프로브를 반드시 열어둔다. 이게 막히면 liveness/readiness 가 전부
                    //    401 을 받아 **파드가 계속 죽는다.** (§12.11 에 프로브로 데인 이력이 있다)
                    .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    // 로그인 시작과 콜백은 당연히 미인증으로 들어온다.
                    .requestMatchers("/api/oauth2/**", "/api/login/oauth2/**").permitAll()
                    .anyRequest().authenticated()
            }

            .oauth2Login { oauth2 ->
                oauth2.authorizationEndpoint { it.baseUri("/api/oauth2/authorization") }
                oauth2.redirectionEndpoint { it.baseUri("/api/login/oauth2/code/*") }
                oauth2.userInfoEndpoint { it.userService(oAuth2UserService) }
                // SPA 라 돌아갈 곳은 언제나 루트다. 두 번째 인자 true 가 "저장된 요청을 무시하고
                // 항상 여기로" 를 뜻한다 — 없으면 사용자가 처음 찔렀던 API URL 로 리다이렉트돼
                // JSON 이 브라우저에 그대로 뜬다.
                oauth2.defaultSuccessUrl("/", true)
                oauth2.failureUrl("/?login=failed")
            }

            .logout { logout ->
                logout.logoutUrl("/api/auth/logout")
                logout.deleteCookies(SESSION_COOKIE_NAME)
                logout.invalidateHttpSession(true)
                // 기본 동작은 로그인 페이지로 리다이렉트다. XHR 로 부르는 API 이므로 204 로 끝낸다.
                logout.logoutSuccessHandler { _, response, _ ->
                    response.status = HttpStatus.NO_CONTENT.value()
                }
            }

            .exceptionHandling { ex ->
                // ⚠️ 기본값은 로그인 페이지로 302 다. 프론트가 fetch('/api/watches') 했을 때
                //    302 가 오면 HTML 을 JSON 으로 파싱하려다 엉뚱한 곳에서 실패한다.
                //    API 서버이므로 401 + ProblemDetail 로 끝낸다.
                ex.authenticationEntryPoint { _, response, _ -> writeUnauthorized(response) }
            }

            .csrf { csrf ->
                // 세션 쿠키 인증이므로 CSRF 는 실재하는 위협이다. SameSite=Lax 가 cross-site
                // POST 를 상당 부분 막지만 그건 방어 계층이지 CSRF 대책의 이름이 아니다.
                csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                // ⚠️ Spring Security 6 은 CsrfToken 을 **지연 로딩**한다. 아무도 토큰을 읽지
                //    않으면 XSRF-TOKEN 쿠키가 아예 내려가지 않아서, 프론트가 헤더에 실을 값을
                //    구할 수 없다. attribute name 을 null 로 두면 매 요청 즉시 해석돼 쿠키가 나간다.
                csrf.csrfTokenRequestHandler(
                    CsrfTokenRequestAttributeHandler().apply { setCsrfRequestAttributeName(null) },
                )
            }

        return http.build()
    }

    private fun writeUnauthorized(response: HttpServletResponse) {
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write(
            """{"type":"about:blank","title":"Unauthorized","status":401,"detail":"로그인이 필요합니다"}""",
        )
    }

    companion object {
        /** `application.yml` 의 `server.servlet.session.cookie.name` 과 같아야 한다. */
        const val SESSION_COOKIE_NAME = "ALIMISESSION"
    }
}
