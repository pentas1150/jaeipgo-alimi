package com.jaeipgo.alimi.core.product

import org.springframework.data.jpa.repository.JpaRepository

interface ProductRepository : JpaRepository<Product, Long> {

    /**
     * `uk_product_external` 을 그대로 타는 조회.
     */
    fun findByPlatformAndStoreIdAndExternalProductNo(
        platform: Platform,
        storeId: String,
        externalProductNo: String,
    ): Product?

    /**
     * 상품번호만으로 찾는다 (`idx_product_no`).
     *
     * 등록할 때 **이쪽을 먼저 본다.** 네이버 상품번호는 전역 시퀀스로 보이므로,
     * 판매자가 스토어 슬러그를 바꿔도 같은 상품이다. UNIQUE 키에는 `store_id` 가
     * 들어 있어 그대로 INSERT 하면 같은 상품이 두 행이 되고 **알림이 두 번 나간다.**
     */
    fun findByPlatformAndExternalProductNo(
        platform: Platform,
        externalProductNo: String,
    ): Product?
}
