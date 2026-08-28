// 백엔드 도메인 API 가 아직 없으므로, 지금은 배선 확인용 샘플 엔드포인트를 호출한다.
// 실제 도메인이 생기면 POST /api/watches 로 바꾼다.
const form = document.querySelector('#watch-form')
const result = document.querySelector('#result')

form.addEventListener('submit', async (e) => {
  e.preventDefault()
  result.textContent = '신청 중...'
  result.className = 'result'

  const url = form.url.value.trim()
  const target = form.target.value.trim()

  try {
    const res = await fetch('/api/notifications', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        recipient: target,
        channel: target.includes('@') ? 'EMAIL' : 'WEBHOOK',
        title: '재입고 알림 신청',
        content: url,
      }),
    })

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
