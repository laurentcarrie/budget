package com.perso.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class S3SigningTest {
    /** The "GET Bucket (List Objects)" example of the AWS Signature V4 documentation. */
    @Test fun listObjectsMatchesTheAwsDocumentationExample() {
        val auth = S3.authorization(
            method = "GET", host = "examplebucket.s3.amazonaws.com", path = "/",
            query = S3.canonicalQuery(mapOf("prefix" to "J", "max-keys" to "2")),
            payloadHash = S3.EMPTY_SHA256, amzDate = "20130524T000000Z", region = "us-east-1",
            keyId = "AKIAIOSFODNN7EXAMPLE", secret = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
                "SignedHeaders=host;x-amz-content-sha256;x-amz-date, " +
                "Signature=34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7", auth)
    }

    @Test fun emptyPayloadHash() = assertEquals(S3.EMPTY_SHA256, S3.sha256Hex(ByteArray(0)))

    @Test fun queryEncodingAndOrder() {
        assertEquals("budget%2F", S3.enc("budget/"))
        assertEquals("a%20b~_-.", S3.enc("a b~_-."))
        assertEquals("list-type=2&prefix=budget%2F", S3.canonicalQuery(mapOf("prefix" to "budget/", "list-type" to "2")))
    }

    @Test fun parseListPage() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <ListBucketResult><Name>b</Name><Prefix>budget/</Prefix><IsTruncated>true</IsTruncated>
            <NextContinuationToken>tok+/=</NextContinuationToken>
            <Contents><Key>budget/</Key><ETag>&quot;d41d8&quot;</ETag></Contents>
            <Contents><Key>budget/ticket_1_ab12.json</Key><LastModified>2026-11-21T17:00:00.000Z</LastModified><ETag>&quot;aaa111&quot;</ETag></Contents>
            <Contents><Key>budget/ticket_1_ab12.jpg</Key><ETag>&quot;bbb222&quot;</ETag></Contents>
            <Contents><Key>budget/old/x.json</Key><ETag>&quot;ccc&quot;</ETag></Contents>
            </ListBucketResult>"""
        val page = S3.parseList(xml, "budget/")
        assertEquals(listOf(S3.Obj("ticket_1_ab12.json", "aaa111"), S3.Obj("ticket_1_ab12.jpg", "bbb222")), page.objects)
        assertEquals("tok+/=", page.nextToken)
    }

    @Test fun lastPageHasNoToken() {
        val xml = "<ListBucketResult><IsTruncated>false</IsTruncated></ListBucketResult>"
        assertNull(S3.parseList(xml, "budget/").nextToken)
    }
}
