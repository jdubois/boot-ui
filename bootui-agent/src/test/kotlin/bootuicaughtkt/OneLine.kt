package bootuicaughtkt

/** kotlinc's one-line try/catch expressions: the caught-exceptions visit must take their handlers for the application's. */
fun parse(text: String): Int { val value = try { text.toInt() } catch (e: NumberFormatException) { -1 }; return value }

fun guarded(value: Any?): Int = try { value!!.hashCode() } catch (t: Throwable) { -2 }
