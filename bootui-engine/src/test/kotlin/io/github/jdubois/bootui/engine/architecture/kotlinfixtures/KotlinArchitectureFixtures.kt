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
