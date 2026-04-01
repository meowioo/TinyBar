package com.example.tinybar.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

private object TiebaApiConst {
    const val APP_BASE_HOST = "http://tiebac.baidu.com"
}

class TiebaHttpClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {

    suspend fun postProto(
        path: String,
        cmd: Int,
        bytes: ByteArray
    ): ByteArray = withContext(Dispatchers.IO) {
        val partHeaders = Headers.headersOf(
            "Content-Disposition",
            "form-data; name=\"data\"; filename=\"file\""
        )

        val multipartBody = MultipartBody.Builder("-*_r1999")
            .setType(MultipartBody.FORM)
            .addPart(partHeaders, bytes.toRequestBody(null))
            .build()

        val request = Request.Builder()
            .url("${TiebaApiConst.APP_BASE_HOST}$path?cmd=$cmd")
            .post(multipartBody)
            .header("User-Agent", "TinyBar/0.1")
            .header("Host", "tiebac.baidu.com")
            .header("Connection", "keep-alive")
            .header("x_bd_data_type", "protobuf")
            // 不要手动加 Accept-Encoding
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code}: ${resp.message}")
            }
            resp.body?.bytes() ?: throw IOException("Empty body")
        }
    }
}