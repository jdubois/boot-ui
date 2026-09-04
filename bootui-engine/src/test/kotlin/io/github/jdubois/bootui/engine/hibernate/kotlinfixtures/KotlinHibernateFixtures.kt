package io.github.jdubois.bootui.engine.hibernate.kotlinfixtures

import jakarta.persistence.Entity
import jakarta.persistence.Id

/**
 * Kotlin fixtures for the Hibernate advisor. They are only read reflectively, never persisted, and
 * exist so the entity rules are verified against what the Kotlin compiler actually emits for a
 * property: a backing field plus an accessor pair, where the field's visibility is a compilation
 * detail rather than a decision the author made.
 */

/**
 * `lateinit var` leaves its backing field public so the initialisation check can run from outside the
 * class, while every read and write still goes through `getName` / `setName`. Kotlin offers no way to
 * make that field private, so reporting it asks the author for a change they cannot make.
 */
@Entity
class KotlinLateinitEntity {

    @Id
    var id: Long = 0

    lateinit var name: String

    /** Reads as "is" but is not the boolean spelling: the accessors are `getIsoCode` / `setIsoCode`. */
    lateinit var isoCode: String

    /** The boolean spelling, whose accessors are `isActive` / `setActive`. */
    var isActive: Boolean = false
}

/**
 * `@JvmField` is the opposite: the author asked for a plain public field, and the compiler generates
 * no accessors at all. That is the same encapsulation break a Java public field is, so it stays
 * reported.
 */
@Entity
class KotlinJvmFieldEntity {

    @Id
    var id: Long = 0

    @JvmField
    var code: String = ""
}
