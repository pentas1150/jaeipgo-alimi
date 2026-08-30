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

// 백엔드 도메인 API 가 아직 없으므로 지금은 배선 확인용 샘플 엔드포인트를 호출한다.
// 3단계에서 POST /api/watches { productUrl } 로 바꾼다.
form.addEventListener('submit', async (e) => {
  e.preventDefault()
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
        content: form.url.value.trim(),
      }),
    })

    if (res.status === 401) {
      renderSignedOut()
      throw new Error('로그인이 만료되었습니다. 다시 로그인해주세요.')
    }
    if (!res.ok) throw new Error(`서버 응답 ${res.status}`)

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
