package io.github.jdubois.bootui.engine.architecture.kotlinfixtures

import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * The shapes reported in #1176: a transactional use case decomposed into several transactional functions of
 * one service. Each self-call only joins the transaction its caller already runs in, so skipping the proxy loses
 * nothing and ARCH-SPRING-004 must not report any of them. Functions are `open` because these fixtures are not
 * compiled with the kotlin-spring plugin.
 */
@Service
open class KotlinSubscriptionService {

    @Transactional
    open fun unsubscribe(token: String) {
        unsubscribe(UUID.fromString(token))
    }

    @Transactional
    open fun unsubscribe(token: UUID) {
        token.toString()
    }

    /** The call omits a default argument, so it reaches `resolveAddress` through its `$default` bridge. */
    @Transactional
    open fun createSilently(email: String) {
        resolveAddress(email)
    }

    @Transactional(readOnly = true)
    open fun resolveAddress(email: String, normalize: Boolean = true): String =
        if (normalize) email.lowercase() else email

    @Transactional(propagation = Propagation.MANDATORY)
    open fun admitInvitee(email: String) {
        addToAllowlist(email)
    }

    @Transactional
    open fun addToAllowlist(email: String) {
        email.length
    }
}

/** A private helper of class-level transactional functions runs inside their transaction. */
@Service
@Transactional
open class KotlinUsernameService {

    open fun claim(username: String) {
        requireAvailable(username)
    }

    open fun rename(username: String) {
        requireAvailable(username)
    }

    private fun requireAvailable(username: String) {
        check(isAvailable(username)) { "$username is taken" }
    }

    @Transactional(readOnly = true)
    open fun isAvailable(username: String): Boolean = username.isNotBlank()
}
