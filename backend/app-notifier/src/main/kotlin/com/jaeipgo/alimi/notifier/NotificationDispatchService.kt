package com.jaeipgo.alimi.notifier

import com.jaeipgo.alimi.contract.NotificationDispatch
import com.jaeipgo.alimi.core.notification.NotificationLog
import com.jaeipgo.alimi.core.notification.NotificationLogRepository
import com.jaeipgo.alimi.core.notification.NotificationLogStatus
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 발송 이력 관리. 멱등성의 실제 구현이 여기 있다.
 *
 * ⚠️ 각 메서드가 **독립 트랜잭션**이다. 리스너를 통째로 @Transactional 로 감싸면 안 된다 —
 * 발송이 실패해 예외를 던질 때 이력까지 롤백되어 "몇 번 시도했는지"가 사라진다.
 */
@Service
class NotificationDispatchService(
    private val repository: NotificationLogRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 처리를 선점한다.
     *
     * @return 진행해야 하면 이력 id, 이미 발송 완료됐으면 null
     *
     * ## 왜 "존재하면 skip" 이 아닌가
     * 단순히 행이 있으면 건너뛰게 만들면 **버그가 된다**:
     * 발송이 실패해 재시도가 돌아왔을 때 이미 행이 있으므로 건너뛰고,
     * 결국 알림이 영영 안 나간다.
     *
     * 그래서 상태를 본다 — SENT 만 진짜 중복이고, PENDING/FAILED 는 재시도 대상이다.
     */
    @Transactional
    fun claim(dispatch: NotificationDispatch): Long? {
        val existing = repository.findByIdempotencyKey(dispatch.idempotencyKey)
        if (existing != null) {
            return if (existing.status == NotificationLogStatus.SENT) {
                null
            } else {
                existing.recordAttempt()
                existing.id
            }
        }

        return try {
            repository.saveAndFlush(
                NotificationLog(
                    watchId = dispatch.watchId,
                    productId = dispatch.productId,
                    channel = dispatch.channel,
                    target = dispatch.target,
                    idempotencyKey = dispatch.idempotencyKey,
                    attemptCount = 1,
                ),
            ).id
        } catch (e: DataIntegrityViolationException) {
            // 같은 키를 두 파드가 동시에 처리하려 한 경우. UNIQUE 제약이 이겼다.
            // 상대가 발송 중이므로 이번 건은 넘긴다.
            log.debug("동시 처리 감지, 건너뜁니다. key={}", dispatch.idempotencyKey, e)
            null
        }
    }

    @Transactional
    fun markSent(logId: Long) {
        repository.findById(logId).ifPresent { it.markSent() }
    }

    @Transactional
    fun markFailed(logId: Long, error: Throwable) {
        repository.findById(logId).ifPresent {
            it.markFailed("${error.javaClass.simpleName}: ${error.message}")
        }
    }
}
