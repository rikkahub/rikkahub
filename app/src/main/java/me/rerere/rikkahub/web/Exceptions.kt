package me.rerere.rikkahub.web

import io.ktor.http.HttpStatusCode

sealed class ApiException(
    override val message: String,
    val status: HttpStatusCode
) : RuntimeException(message)

class BadRequestException(message: String) : ApiException(message, HttpStatusCode.BadRequest)
class NotFoundException(message: String) : ApiException(message, HttpStatusCode.NotFound)
