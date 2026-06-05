package me.rerere.rikkahub.web

import io.ktor.http.HttpStatusCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WebApiErrorMappingTest {

    @Test
    fun `unhandled exception does not leak raw message`() {
        val secret = "secret path /data/data/me.rerere.rikkahub/files/leak.txt"
        val (status, body) = mapThrowableToErrorResponse(RuntimeException(secret))

        assertEquals(HttpStatusCode.InternalServerError, status)
        assertEquals(500, body.code)
        assertEquals("Internal server error", body.error)
        assertFalse(body.error.contains("secret path"))
        assertFalse(body.error.contains("/data/"))
    }

    @Test
    fun `BadRequestException keeps its safe client message`() {
        val (status, body) = mapThrowableToErrorResponse(BadRequestException("Invalid file id"))

        assertEquals(HttpStatusCode.BadRequest, status)
        assertEquals(400, body.code)
        assertEquals("Invalid file id", body.error)
    }

    @Test
    fun `UnauthorizedException maps to 401 with its message`() {
        val (status, body) = mapThrowableToErrorResponse(UnauthorizedException("Invalid password"))

        assertEquals(HttpStatusCode.Unauthorized, status)
        assertEquals(401, body.code)
        assertEquals("Invalid password", body.error)
    }
}
