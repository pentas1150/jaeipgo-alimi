package com.jaeipgo.alimi.notifier.send

import com.jaeipgo.alimi.contract.NotificationChannel
import jakarta.mail.internet.MimeMessage
import org.slf4j.LoggerFactory
import org.springframework.mail.MailAuthenticationException
import org.springframework.mail.MailParseException
import org.springframework.mail.MailSendException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 이메일 어댑터.
 *
 * 제목/본문 렌더링은 **여기 안에 갇혀 있다.** 포트에는 그런 개념이 없다 —
 * 그래서 나중에 디스코드 어댑터를 추가해도 이메일 포맷을 건드릴 일이 없다.
 */
class EmailNotificationSender(
    private val mailSender: JavaMailSender,
    private val from: String,
    private val zoneId: ZoneId,
) : NotificationSender {

    private val log = LoggerFactory.getLogger(javaClass)
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    override val channel = NotificationChannel.EMAIL

    override fun send(notification: RestockNotification) {
        val message: MimeMessage = mailSender.createMimeMessage()

        try {
            MimeMessageHelper(message, false, Charsets.UTF_8.name()).apply {
                setFrom(from)
                setTo(notification.target)
                setSubject("[재입고] ${notification.productName}")
                setText(renderHtml(notification), true)
            }
        } catch (e: Exception) {
            // 주소 형식 오류 등. 몇 번을 다시 해도 결과가 같다.
            throw PermanentSendException("메일 구성 실패: target=${notification.target}", e)
        }

        try {
            mailSender.send(message)
            log.info("메일 발송 완료 target={} product={}", notification.target, notification.productName)
        } catch (e: MailParseException) {
            throw PermanentSendException("메일 파싱 실패", e)
        } catch (e: MailAuthenticationException) {
            // SMTP 인증 실패는 설정 문제다. 재시도해도 안 되고, 운영자가 고쳐야 한다.
            throw PermanentSendException("SMTP 인증 실패 — 설정을 확인하세요", e)
        } catch (e: MailSendException) {
            // 수신 주소 자체가 거부된 경우와 일시적 장애가 섞여 온다.
            // failedMessages 가 있으면 주소 문제로 본다.
            if (e.failedMessages.isNotEmpty()) {
                throw PermanentSendException("수신 거부: target=${notification.target}", e)
            }
            throw TransientSendException("메일 발송 일시 실패", e)
        } catch (e: Exception) {
            throw TransientSendException("메일 발송 실패", e)
        }
    }

    private fun renderHtml(n: RestockNotification): String {
        val detected = formatter.format(n.detectedAt.atZone(zoneId))
        return """
            <div style="font-family:-apple-system,'Apple SD Gothic Neo','Noto Sans KR',sans-serif;
                        max-width:520px;line-height:1.6;color:#1a1a1a">
              <h2 style="margin:0 0 4px;font-size:18px">재입고되었습니다</h2>
              <p style="margin:0 0 20px;color:#6b6b6b;font-size:13px">감지 시각 $detected</p>
              <p style="margin:0 0 20px;font-size:15px"><strong>${escape(n.productName)}</strong></p>
              <a href="${escape(n.productUrl)}"
                 style="display:inline-block;padding:10px 18px;background:#0b7a3b;color:#fff;
                        text-decoration:none;border-radius:6px;font-weight:600;font-size:14px">
                상품 보러 가기
              </a>
              <p style="margin:24px 0 0;color:#8a8a8a;font-size:12px">
                재고는 금방 소진될 수 있습니다.
              </p>
            </div>
        """.trimIndent()
    }

    /** 상품명은 외부에서 온 값이다. 그대로 HTML 에 박으면 안 된다. */
    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}

