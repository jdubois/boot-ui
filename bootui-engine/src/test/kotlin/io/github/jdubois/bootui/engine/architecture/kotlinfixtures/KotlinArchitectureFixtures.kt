package io.github.jdubois.bootui.engine.architecture.kotlinfixtures

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Kotlin fixtures for the Architecture advisor. They are only scanned as bytecode by ArchUnit, never
 * executed, and exist so the Kotlin-awareness of the rules is verified against what the Kotlin
 * compiler actually emits: a file facade for the top-level functions below, `componentN`/`copy`
 * accessors and `$default` bridges for the data class, a `Companion` holder, and the
 * `Continuation`-based shape of a suspending function.
 */

/** Top-level function: compiles into the `KotlinArchitectureFixturesKt` file facade. */
fun formatOrderId(id: Long, prefix: String = "ORD-"): String = "$prefix$id"

/** Second top-level function, so the facade holds more than one member. */
fun parseOrderId(reference: String): Long = reference.removePrefix("ORD-").toLong()

/** Data class: `component1`, `component2`, `copy` and `copy$default` are generated, not written. */
data class KotlinOrder(val id: Long, val customer: String)

/** Ordinary class with a hand-written `copy`: it must stay visible to the rules. */
class KotlinOrderDraft(val customer: String) {
    fun copy(): KotlinOrderDraft = KotlinOrderDraft(customer)
}

/** Kotlin singleton: compiles to a final class with an `INSTANCE` field and instance methods. */
object KotlinOrderCodes {
    const val CREATED = "CREATED"

    fun describe(code: String): String = "order is $code"
}

@Service
open class KotlinOrderService {

    companion object {
        private val log = LoggerFactory.getLogger(KotlinOrderService::class.java)
    }

    /** Supported: Spring adapts a suspending `@Scheduled` function through the coroutine bridge. */
    @Scheduled(fixedDelay = 5_000L)
    open suspend fun refreshOrders() {
        log.debug("refreshing orders")
    }

    /** Flagged: the declared result is discarded exactly like a synchronous return value. */
    @Scheduled(fixedRate = 1_000L)
    open suspend fun countOrders(): Long = 0L

    /** Flagged: `@Async` does not support suspending functions. */
    @Async
    open suspend fun notifyCustomer() {
        log.debug("notifying customer")
    }

    /** Supported: a plain suspending function carries no proxy expectation. */
    open suspend fun loadOrder(id: Long): KotlinOrder = KotlinOrder(id, "sample")

    /** Flagged: a private method is never intercepted, in Kotlin exactly as in Java. */
    @Transactional
    private fun auditOrder(id: Long) {
        log.debug("auditing {}", id)
    }

    fun audit(id: Long) = auditOrder(id)
}

/**
 * Default arguments, which have no Java equivalent: `expire(id)` does not compile into a call to
 * `expire`. The compiler emits an `expire$default` bridge that fills in the missing argument and then
 * calls the real function, so one call written in Kotlin becomes two calls in the same class.
 */
@Service
open class KotlinCartService {

    companion object {
        private val log = LoggerFactory.getLogger(KotlinCartService::class.java)
    }

    @Transactional
    open fun expire(id: Long, reason: String = "expired") {
        log.debug("expiring {} because {}", id, reason)
    }

    /** A real self-invocation, which the `$default` bridge must not hide. */
    fun expireNow(id: Long) = expire(id)

    /**
     * A self-invocation from inside a lambda, which the compiler puts in a synthetic method of this
     * class. Synthetic or not, it is code the developer wrote and a real proxy bypass. `Runnable` is a
     * SAM type rather than a Kotlin `forEach`, whose lambda would be inlined into the caller.
     */
    fun expireLater(id: Long) = Runnable { expire(id, "deferred") }
}

/**
 * The idiomatic Kotlin exception hierarchy: variants are nested inside the sealed parent so the
 * compiler can close the hierarchy, and they are read as `KotlinClaimException.AlreadyAssigned` at
 * every call site.
 */
sealed class KotlinClaimException(message: String) : RuntimeException(message) {

    class AlreadyAssigned(id: Long) : KotlinClaimException("claim $id is already assigned")

    class Expired(id: Long) : KotlinClaimException("claim $id has expired")
}
