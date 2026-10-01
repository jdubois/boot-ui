package io.github.jdubois.bootui.engine.architecture.kotlininjectionfixtures

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

class KotlinNamespaceRepository

/**
 * Constructor injection, the shape ARCH-SPRING-001 recommends. Kotlin writes an unqualified annotation on a
 * constructor property to the parameter and, because @Value and @Autowired also target FIELD, to the backing
 * field as well (#1175). None of these fields is field injection.
 */
@Component
class KotlinConstructorInjectedComponent(
    private val repository: KotlinNamespaceRepository,
    @Value("\${spring.session.data.redis.namespace:spring:session}") private val sessionNamespace: String,
    @Value("\${bootui.fixture.retries:3}") var retries: Int,
    @Autowired private val fallbackRepository: KotlinNamespaceRepository,
) {
    fun describe(): String = "$repository $sessionNamespace $retries $fallbackRepository"
}

/** Genuine field injection in Kotlin: each of these fields stays an ARCH-SPRING-001 finding. */
@Component
class KotlinFieldInjectedComponent(
    @param:Value("\${bootui.fixture.parameter:a}") @field:Value("\${bootui.fixture.field:b}")
    private val mismatched: String,
) {
    @Autowired
    lateinit var repository: KotlinNamespaceRepository

    @Value("\${bootui.fixture.body:default}")
    val bodyProperty: String = ""

    fun describe(): String = "$mismatched $repository $bodyProperty"
}
