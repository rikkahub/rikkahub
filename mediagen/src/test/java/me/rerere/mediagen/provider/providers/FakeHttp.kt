package me.rerere.mediagen.provider.providers

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

internal fun fakeClient(
    requests: MutableList<Request> = mutableListOf(),
    respond: (Request) -> Response,
): OkHttpClient = OkHttpClient.Builder()
    .addInterceptor { chain ->
        requests += chain.request()
        respond(chain.request())
    }
    .build()

internal fun jsonResponse(request: Request, body: String, code: Int = 200): Response =
    Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

internal fun Request.bodyAsString(): String =
    Buffer().also { body!!.writeTo(it) }.readUtf8()
