// 프론트와 API 는 같은 오리진이다 (nginx 가 /api/ 를 프록시한다 — DESIGN §10.3).
// 덕분에 CORS 설정이 전혀 없고, 세션 쿠키도 그냥 따라간다.

const account = document.querySelector('#account')
const signedOut = document.querySelector('#signed-out')
const form = document.querySelector('#watch-form')
const result = document.querySelector('#result')

/**
 * CSRF 토큰을 쿠키에서 읽는다.
 *
 * 서버가 CookieCsrfTokenRepository.withHttpOnlyFalse() 로 XSRF-TOKEN 을 내려주고,
 * 상태 변경 요청에는 같은 값을 X-XSRF-TOKEN 헤더로 되돌려줘야 한다.
 * 세션 쿠키로 인증하는 이상 CSRF 는 실재하는 위협이라 끄지 않는다.
 */
function csrfToken() {
  const hit = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))
  return hit ? decodeURIComponent(hit.slice('XSRF-TOKEN='.length)) : ''
}

/**
 * 서버가 준 RFC 7807 ProblemDetail 에서 사람이 읽을 문장을 뽑는다.
 *
 * GlobalExceptionHandler 가 검증 실패를 `errors: { 필드: 메시지 }` 로 담아주므로
 * "서버 응답 400" 대신 어느 입력이 왜 틀렸는지 그대로 보여줄 수 있다.
 */
async function problemMessage(res) {
  try {
    const problem = await res.json()
    const fieldErrors = problem.errors && Object.values(problem.errors)
    if (fieldErrors && fieldErrors.length) return fieldErrors.join(' / ')
    if (problem.detail) return problem.detail
  } catch {
    // 본문이 JSON 이 아니면 상태 코드로 만족한다.
  }
  return `서버 응답 ${res.status}`
}

/** 로그인 상태를 확인한다. 미인증은 401 이므로 예외가 아니라 정상 분기다. */
async function loadMe() {
  const res = await fetch('/api/auth/me')
  if (res.status === 401) return null
  if (!res.ok) throw new Error(`서버 응답 ${res.status}`)
  return res.json()
}

function renderSignedIn(me) {
  account.hidden = false
  account.innerHTML = ''

  const who = document.createElement('span')
  who.className = 'who'
  who.textContent = me.email          // textContent 로 넣어 HTML 주입 여지를 없앤다

  const logout = document.createElement('button')
  logout.type = 'button'
  logout.className = 'linkish'
  logout.textContent = '로그아웃'
  logout.addEventListener('click', async () => {
    await fetch('/api/auth/logout', {
      method: 'POST',
      headers: { 'X-XSRF-TOKEN': csrfToken() },
    })
    location.reload()
  })

  account.append(who, logout)
  signedOut.hidden = true
  form.hidden = false
}

function renderSignedOut() {
  account.hidden = true
  signedOut.hidden = false
  form.hidden = true
}

let currentUser = null

/**
 * 상품 URL 을 대충 훑는다.
 *
 * ⚠️ 일부러 백엔드보다 **느슨하다.** 프론트 검증의 목적은 보안이 아니라 UX 다 —
 * 서버 왕복 없이 오타를 바로 알려주는 것뿐이고, curl 한 줄이면 우회된다.
 * 진짜 방어선은 백엔드의 @SmartStoreUrl 이다.
 *
 * 여기를 백엔드만큼 엄격하게 만들면 나중에 백엔드가 지원 범위를 넓혔을 때
 * 프론트가 막아서 버그가 된다. 그래서 호스트만 보고 나머지는 서버에 맡긴다.
 */
function looksLikeSmartStoreUrl(value) {
  return /(^|\/\/)(m\.)?smartstore\.naver\.com\//i.test(value)
}

// 백엔드 도메인 API 가 아직 없으므로 지금은 배선 확인용 샘플 엔드포인트를 호출한다.
// 6단계에서 POST /api/watches { productUrl } 로 바꾼다.
form.addEventListener('submit', async (e) => {
  e.preventDefault()

  const productUrl = form.url.value.trim()
  if (!looksLikeSmartStoreUrl(productUrl)) {
    result.textContent =
      '네이버 스마트스토어 상품 URL 을 입력해주세요 (https://smartstore.naver.com/{스토어}/products/{번호})'
    result.className = 'result err'
    return
  }

  result.textContent = '신청 중...'
  result.className = 'result'

  try {
    const res = await fetch('/api/notifications', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-XSRF-TOKEN': csrfToken(),
      },
      body: JSON.stringify({
        // 수신 주소는 더 이상 사용자가 입력하지 않는다. 로그인한 계정의 이메일이다.
        recipient: currentUser.email,
        channel: 'EMAIL',
        title: '재입고 알림 신청',
        content: productUrl,
      }),
    })

    if (res.status === 401) {
      renderSignedOut()
      throw new Error('로그인이 만료되었습니다. 다시 로그인해주세요.')
    }
    if (!res.ok) throw new Error(await problemMessage(res))

    const body = await res.json()
    result.textContent = `신청 완료 (#${body.id}). 재입고되면 알려드립니다.`
    result.classList.add('ok')
    form.reset()
  } catch (err) {
    result.textContent = `실패: ${err.message}`
    result.classList.add('err')
  }
})

// 진입 시 로그인 상태를 한 번 확인한다.
try {
  currentUser = await loadMe()
  if (currentUser) renderSignedIn(currentUser)
  else renderSignedOut()
} catch (err) {
  renderSignedOut()
  result.textContent = `상태 확인 실패: ${err.message}`
  result.classList.add('err')
}

// 구글 로그인이 실패하면 SecurityConfig 의 failureUrl 이 여기로 돌려보낸다.
if (new URLSearchParams(location.search).get('login') === 'failed') {
  result.textContent = '로그인에 실패했습니다. 다시 시도해주세요.'
  result.classList.add('err')
}
