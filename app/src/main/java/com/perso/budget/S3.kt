package com.perso.budget

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Minimal S3 PutObject with AWS Signature V4 (no SDK). */
object S3 {
    const val PREFS = "aws"

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

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun sha256(b: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(b))
    private fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray())

    /** [name] is relative to the configured prefix and must only contain [A-Za-z0-9._-]. */
    fun put(cfg: Config, name: String, body: ByteArray, contentType: String) {
        require(Regex("[A-Za-z0-9._-]+").matches(name)) { "bad object name $name" }
        val region = cfg.region
        val host = "${cfg.bucket}.s3.$region.amazonaws.com"
        val path = "/${cfg.prefix}/$name"
        val now = Date()
        val amzDate = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(now)
        val day = amzDate.substring(0, 8)
        val payloadHash = sha256(body)

        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
        val canonical = listOf("PUT", path, "",
            "host:$host\nx-amz-content-sha256:$payloadHash\nx-amz-date:$amzDate\n", signedHeaders, payloadHash).joinToString("\n")
        val scope = "$day/$region/s3/aws4_request"
        val toSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n${sha256(canonical.toByteArray())}"
        var k = hmac("AWS4${cfg.secret}".toByteArray(), day)
        k = hmac(k, region); k = hmac(k, "s3"); k = hmac(k, "aws4_request")
        val signature = hex(hmac(k, toSign))

        val c = URL("https://$host$path").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "PUT"
            c.doOutput = true
            c.connectTimeout = 15000; c.readTimeout = 60000
            c.setRequestProperty("x-amz-date", amzDate)
            c.setRequestProperty("x-amz-content-sha256", payloadHash)
            c.setRequestProperty("Content-Type", contentType)
            c.setRequestProperty("Authorization",
                "AWS4-HMAC-SHA256 Credential=${cfg.keyId}/$scope, SignedHeaders=$signedHeaders, Signature=$signature")
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            if (code !in 200..299) {
                val err = c.errorStream?.bufferedReader()?.readText()?.take(300) ?: ""
                throw java.io.IOException("S3 $code $err")
            }
        } finally { c.disconnect() }
    }
}
