package me.rerere.rikkahub.data.sync.s3

import java.net.URLEncoder
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object AwsSignatureV4 {
    private const val ALGORITHM = "AWS4-HMAC-SHA256"
    private const val SERVICE = "s3"
    private const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    data class SignedRequest(
        val headers: Map<String, String>,
        val url: String,
    )

    fun sign(
        config: S3Config,
        method: String,
        path: String,
        queryParams: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        payload: ByteArray? = null,
        contentType: String? = null,
    ): SignedRequest {
        val now = ZonedDateTime.now(ZoneOffset.UTC)
        val dateStamp = now.format(dateFormatter)
        val amzDate = now.format(timestampFormatter)

        val payloadHash = payload?.sha256Hex() ?: UNSIGNED_PAYLOAD

        val host = config.host
        val canonicalUri = if (config.pathStyle) {
            "/${config.bucket}$path"
        } else {
            path
        }.let { if (it.isEmpty()) "/" else it }

        val allHeaders = mutableMapOf(
            "host" to (if (config.pathStyle) host else "${config.bucket}.$host"),
            "x-amz-content-sha256" to payloadHash,
            "x-amz-date" to amzDate,
        )
        contentType?.let { allHeaders["content-type"] = it }
        payload?.let { allHeaders["content-length"] = it.size.toString() }
        allHeaders.putAll(headers.mapKeys { it.key.lowercase() })

        val signedHeaders = allHeaders.keys.sorted().joinToString(";")
        val canonicalHeaders = allHeaders.entries
            .sortedBy { it.key }
            .joinToString("") { "${it.key}:${it.value.trim()}\n" }

        val canonicalQueryString = queryParams.entries
            .sortedBy { it.key }
            .joinToString("&") { "${it.key.urlEncode()}=${it.value.urlEncode()}" }

        val canonicalRequest = buildString {
            appendLine(method)
            appendLine(canonicalUri.urlEncodePath())
            appendLine(canonicalQueryString)
            append(canonicalHeaders)
            appendLine()
            appendLine(signedHeaders)
            append(payloadHash)
        }

        val credentialScope = "$dateStamp/${config.region}/$SERVICE/aws4_request"
        val stringToSign = buildString {
            appendLine(ALGORITHM)
            appendLine(amzDate)
            appendLine(credentialScope)
            append(canonicalRequest.sha256Hex())
        }

        val signingKey = getSignatureKey(
            config.secretAccessKey,
            dateStamp,
            config.region,
            SERVICE
        )
        val signature = hmacSha256(signingKey, stringToSign).toHexString()

        val authorizationHeader = buildString {
            append("$ALGORITHM ")
            append("Credential=${config.accessKeyId}/$credentialScope, ")
            append("SignedHeaders=$signedHeaders, ")
            append("Signature=$signature")
        }

        val resultHeaders = allHeaders.toMutableMap()
        resultHeaders["authorization"] = authorizationHeader

        val url = buildString {
            append(if (config.isHttps) "https://" else "http://")
            append(if (config.pathStyle) host else "${config.bucket}.$host")
            append(canonicalUri)
            if (canonicalQueryString.isNotEmpty()) {
                append("?$canonicalQueryString")
            }
        }

        return SignedRequest(
            headers = resultHeaders,
            url = url
        )
    }

    private fun getSignatureKey(
        key: String,
        dateStamp: String,
        region: String,
        service: String
    ): ByteArray {
        val kDate = hmacSha256("AWS4$key".toByteArray(), dateStamp)
        val kRegion = hmacSha256(kDate, region)
        val kService = hmacSha256(kRegion, service)
        return hmacSha256(kService, "aws4_request")
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun ByteArray.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(this).toHexString()
    }

    private fun String.sha256Hex(): String {
        return this.toByteArray(Charsets.UTF_8).sha256Hex()
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }

    private fun String.urlEncode(): String {
        return URLEncoder.encode(this, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")
    }

    private fun String.urlEncodePath(): String {
        return split("/").joinToString("/") { segment ->
            if (segment.isEmpty()) segment else segment.urlEncode()
        }
    }
}
