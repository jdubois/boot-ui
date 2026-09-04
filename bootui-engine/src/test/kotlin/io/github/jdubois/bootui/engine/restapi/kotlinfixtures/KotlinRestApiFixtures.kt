package io.github.jdubois.bootui.engine.restapi.kotlinfixtures

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Kotlin REST fixtures. Suspending handler methods are the point: each one compiles to a method with
 * a trailing `Continuation<? super T>` parameter and an erased `Object` return type, so the model
 * builder has to read the declared result type out of the continuation rather than from the JVM
 * return type. Bytecode-scanned only, never executed.
 */
data class KotlinOrderDto(val id: Long, val customer: String)

@RestController
@RequestMapping("/api/kotlin-orders")
class KotlinOrderController {

    @GetMapping("/{id}")
    suspend fun findOrder(@PathVariable("id") id: Long): KotlinOrderDto = KotlinOrderDto(id, "sample")

    @GetMapping
    suspend fun listOrders(@RequestParam("page") page: Int): List<KotlinOrderDto> = emptyList()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun createOrder(@RequestBody order: KotlinOrderDto) {
        // no result: the declared type is kotlin.Unit, the JVM return type is Object
    }
}
