package me.rerere.rikkahub.data.sync.s3

import java.net.URLEncoder
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration

internal object AwsSignatureV4 {
    private const val ALGORITHM = "AWS4-HMAC-SHA256"
    private const val SERVICE = "s3"
    private const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"
    private const val MAX_PRESIGN_SECONDS = 7 * 24 * 60 * 60L

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
        payloadHash: String? = null,
        contentLength: Long? = null,
        contentType: String? = null,
    ): SignedRequest {
        val now = ZonedDateTime.now(ZoneOffset.UTC)
        val dateStamp = now.format(dateFormatter)
        val amzDate = now.format(timestampFormatter)

        val resolvedPayloadHash = payloadHash ?: payload?.sha256Hex() ?: UNSIGNED_PAYLOAD

        val host = requestHost(config)
        val canonicalUri = canonicalUri(config, path)

        val allHeaders = mutableMapOf(
            "host" to host,
            "x-amz-content-sha256" to resolvedPayloadHash,
            "x-amz-date" to amzDate,
        )
        contentType?.let { allHeaders["content-type"] = it }
        payload?.let { allHeaders["content-length"] = it.size.toString() }
        contentLength?.let { allHeaders["content-length"] = it.toString() }
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
            append(resolvedPayloadHash)
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
            append(host)
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

    /**
     * 生成带签名的临时 GET 地址。签名放在查询参数里，拿到地址的人在 [expires] 内不需要凭据就能下载，
     * 过期后存储服务返回 403；对象本身不受影响。
     */
    fun presignGetUrl(
        config: S3Config,
        path: String,
        expires: Duration,
        now: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC),
    ): String {
        val expiresSeconds = expires.inWholeSeconds
        require(expiresSeconds in 1..MAX_PRESIGN_SECONDS) {
            "expires must be between 1 second and 7 days"
        }

        val dateStamp = now.format(dateFormatter)
        val amzDate = now.format(timestampFormatter)
        val host = requestHost(config)
        val encodedPath = canonicalUri(config, path).urlEncodePath()
        val credentialScope = "$dateStamp/${config.region}/$SERVICE/aws4_request"

        // 只签 host：拉取方不会带任何额外请求头
        val canonicalQueryString = mapOf(
            "X-Amz-Algorithm" to ALGORITHM,
            "X-Amz-Credential" to "${config.accessKeyId}/$credentialScope",
            "X-Amz-Date" to amzDate,
            "X-Amz-Expires" to expiresSeconds.toString(),
            "X-Amz-SignedHeaders" to "host",
        ).entries
            .sortedBy { it.key }
            .joinToString("&") { "${it.key.urlEncode()}=${it.value.urlEncode()}" }

        val canonicalRequest = buildString {
            appendLine("GET")
            appendLine(encodedPath)
            appendLine(canonicalQueryString)
            appendLine("host:$host")
            appendLine()
            appendLine("host")
            append(UNSIGNED_PAYLOAD)
        }

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

        return buildString {
            append(if (config.isHttps) "https://" else "http://")
            append(host)
            append(encodedPath)
            append("?$canonicalQueryString")
            append("&X-Amz-Signature=$signature")
        }
    }

    private fun requestHost(config: S3Config): String {
        val host = config.host
        return if (config.pathStyle || host.startsWith("${config.bucket}.")) {
            host
        } else {
            "${config.bucket}.$host"
        }
    }

    private fun canonicalUri(config: S3Config, path: String): String {
        return if (config.pathStyle) {
            "/${config.bucket}$path"
        } else {
            path
        }.let { if (it.isEmpty()) "/" else it }
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
