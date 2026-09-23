package io.github.jdubois.bootui.engine.architecture.openapifixtures.altered

import org.springframework.web.context.request.NativeWebRequest

import jakarta.servlet.http.HttpServletResponse
import java.io.IOException

// OpenAPI Generator kotlin-spring template with one changed constant; it must not match the fingerprint.
object ApiUtil {
    fun setExampleResponse(req: NativeWebRequest, contentType: String, example: String) {
        try {
            val res = req.getNativeResponse(HttpServletResponse::class.java)
            res?.characterEncoding = "US-ASCII"
            res?.addHeader("Content-Type", contentType)
            res?.writer?.print(example)
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }
}
