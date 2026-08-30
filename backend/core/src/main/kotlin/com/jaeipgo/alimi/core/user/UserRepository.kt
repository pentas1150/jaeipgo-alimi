package com.jaeipgo.alimi.core.user

import org.springframework.data.jpa.repository.JpaRepository

interface UserRepository : JpaRepository<User, Long> {

    fun findByProviderAndProviderUserId(provider: AuthProvider, providerUserId: String): User?

    fun findByEmail(email: String): User?
}
