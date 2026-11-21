package com.perso.budget

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Minimal S3 client (PUT, GET, list) with AWS Signature V4, no SDK. */
object S3 {
    const val PREFS = "aws"
    const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    fun save(ctx: Context, keyId: String, secret: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("key_id", keyId.trim()).putString("secret", secret.trim()).apply()
    }

    class Config(ctx: Context) {
        private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val keyId = prefs.getString("key_id", "")!!.trim()
        val secret = prefs.getString("secret", "")!!.trim()
        val region = ctx.getString(R.string.aws_region)
        val bucket = ctx.getString(R.string.aws_bucket)
        val prefix = ctx.getString(R.string.aws_prefix)
        val configured get() = keyId.isNotEmpty() && secret.isNotEmpty()
    }

    /** An object of the listing: [name] is relative to the configured prefix, [etag] has no quotes. */
    data class Obj(val name: String, val etag: String)
    data class Page(val objects: List<Obj>, val nextToken: String?)

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun sha256Hex(b: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(b))
    private fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray())

    fun amzDate(now: Date): String =
        SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(now)

    /** RFC 3986 percent-encoding, as SigV4 wants it for query parameters. */
    fun enc(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt().toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~') append(c)
            else append("%%%02X".format(b.toInt() and 0xff))
        }
    }

    fun canonicalQuery(params: Map<String, String>): String =
        params.toSortedMap().entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }

    /** The Authorization header for a request signed with host, x-amz-content-sha256 and x-amz-date. */
    fun authorization(method: String, host: String, path: String, query: String, payloadHash: String,
                      amzDate: String, region: String, keyId: String, secret: String): String {
        val day = amzDate.substring(0, 8)
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
        val canonical = listOf(method, path, query,
            "host:$host\nx-amz-content-sha256:$payloadHash\nx-amz-date:$amzDate\n", signedHeaders, payloadHash).joinToString("\n")
        val scope = "$day/$region/s3/aws4_request"
        val toSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n${sha256Hex(canonical.toByteArray())}"
        var k = hmac("AWS4$secret".toByteArray(), day)
        k = hmac(k, region); k = hmac(k, "s3"); k = hmac(k, "aws4_request")
        val signature = hex(hmac(k, toSign))
        return "AWS4-HMAC-SHA256 Credential=$keyId/$scope, SignedHeaders=$signedHeaders, Signature=$signature"
    }

    private class Response(val body: ByteArray, val etag: String?)

    private fun request(cfg: Config, method: String, path: String, query: String, body: ByteArray?, contentType: String?): Response {
        val host = "${cfg.bucket}.s3.${cfg.region}.amazonaws.com"
        val amzDate = amzDate(Date())
        val payloadHash = if (body == null) EMPTY_SHA256 else sha256Hex(body)
        val url = "https://$host$path" + if (query.isEmpty()) "" else "?$query"
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000; c.readTimeout = 60000
            c.setRequestProperty("x-amz-date", amzDate)
            c.setRequestProperty("x-amz-content-sha256", payloadHash)
            c.setRequestProperty("Authorization",
                authorization(method, host, path, query, payloadHash, amzDate, cfg.region, cfg.keyId, cfg.secret))
            if (body != null) {
                c.doOutput = true
                if (contentType != null) c.setRequestProperty("Content-Type", contentType)
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            if (code !in 200..299) {
                val err = c.errorStream?.bufferedReader()?.readText()?.take(300) ?: ""
                throw java.io.IOException("S3 $code $err")
            }
            val data = if (method == "GET") c.inputStream.use { it.readBytes() } else ByteArray(0)
            return Response(data, c.getHeaderField("ETag")?.trim('"'))
        } finally { c.disconnect() }
    }

    private fun objectPath(cfg: Config, name: String): String {
        require(Regex("[A-Za-z0-9._-]+").matches(name)) { "bad object name $name" }
        return "/${cfg.prefix}/$name"
    }

    /** [name] is relative to the configured prefix and must only contain [A-Za-z0-9._-]. Returns the ETag. */
    fun put(cfg: Config, name: String, body: ByteArray, contentType: String): String? =
        request(cfg, "PUT", objectPath(cfg, name), "", body, contentType).etag

    fun get(cfg: Config, name: String): ByteArray =
        request(cfg, "GET", objectPath(cfg, name), "", null, null).body

    /** Every object under the prefix (follows the continuation tokens). */
    fun list(cfg: Config): List<Obj> {
        val out = mutableListOf<Obj>()
        var token: String? = null
        do {
            val params = mutableMapOf("list-type" to "2", "prefix" to "${cfg.prefix}/")
            token?.let { params["continuation-token"] = it }
            val xml = String(request(cfg, "GET", "/", canonicalQuery(params), null, null).body, Charsets.UTF_8)
            val page = parseList(xml, "${cfg.prefix}/")
            out += page.objects
            token = page.nextToken
        } while (token != null)
        return out
    }

    private fun unescape(s: String) = s.replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    /** Parses a ListObjectsV2 response; names lose [prefix], and sub-folders and the prefix itself are skipped. */
    fun parseList(xml: String, prefix: String): Page {
        val objs = Regex("<Contents>(.*?)</Contents>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
            val key = Regex("<Key>(.*?)</Key>", RegexOption.DOT_MATCHES_ALL).find(m.groupValues[1])?.groupValues?.get(1)?.let(::unescape)
                ?: return@mapNotNull null
            val etag = Regex("<ETag>(.*?)</ETag>", RegexOption.DOT_MATCHES_ALL).find(m.groupValues[1])?.groupValues?.get(1)
                ?.let(::unescape)?.trim('"') ?: ""
            val name = key.removePrefix(prefix)
            if (name.isEmpty() || name.contains('/')) null else Obj(name, etag)
        }.toList()
        val truncated = Regex("<IsTruncated>(.*?)</IsTruncated>").find(xml)?.groupValues?.get(1) == "true"
        val next = if (truncated) Regex("<NextContinuationToken>(.*?)</NextContinuationToken>").find(xml)?.groupValues?.get(1)?.let(::unescape) else null
        return Page(objs, next)
    }
}
