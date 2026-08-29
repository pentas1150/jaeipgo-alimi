package com.jaeipgo.alimi.api

import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat

/**
 * 일회성. CI 가 실패를 실제로 잡는지 확인하기 위해 일부러 깨뜨린 테스트.
 * 빨간불을 확인하면 즉시 되돌린다.
 */
class CiSanityCheckTest {
    @Test
    fun `CI 는 실패를 잡아야 한다`() {
        assertThat(1).isEqualTo(2)
    }
}
