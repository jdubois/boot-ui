package io.github.jdubois.bootui.engine.architecture.kotlinlocationfixtures

// Location fixture: the location tests pin these line numbers, so keep the layout stable when editing.

class KotlinStreamUser {
    fun direct() {
        System.err.println("direct access")
    }

    fun inlined() {
        shout("inlined from another file")
    }

    companion object {
        fun fromCompanion() {
            System.out.println("companion access")
        }
    }
}

fun topLevel() {
    System.out.println("file facade access")
}
