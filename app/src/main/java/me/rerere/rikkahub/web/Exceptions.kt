package me.rerere.rikkahub.web

import io.ktor.http.HttpStatusCode

sealed class ApiException(
    override val message: String,
    val status: HttpStatusCode
) : RuntimeException(message)

class BadRequestException(message: String) : ApiException(message, HttpStatusCode.BadRequest)
class NotFoundException(message: String) : ApiException(message, HttpStatusCode.NotFound)
class UnauthorizedException(message: String) : ApiException(message, HttpStatusCode.Unauthorized)
class ForbiddenException(message: String) : ApiException(message, HttpStatusCode.Forbidden)
