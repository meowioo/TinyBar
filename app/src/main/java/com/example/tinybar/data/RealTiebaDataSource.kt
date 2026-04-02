package com.example.tinybar.data

import android.os.Build
import com.example.tinybar.model.FeedPage
import com.example.tinybar.model.ForumInfo
import com.example.tinybar.model.ForumPage
import com.example.tinybar.model.PostItem
import com.example.tinybar.model.ThreadSummary
import com.example.tinybar.tieba.proto.CommonReq
import com.example.tinybar.tieba.proto.FrsPageReqIdl
import com.example.tinybar.tieba.proto.FrsPageResIdl
import com.example.tinybar.tieba.proto.PbContent
import com.example.tinybar.tieba.proto.PbPageReqIdl
import com.example.tinybar.tieba.proto.PbPageResIdl
import com.example.tinybar.tieba.proto.Post
import com.example.tinybar.tieba.proto.User
import com.example.tinybar.util.TiebaSignUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val PAGE_SIZE = 30
private const val MAIN_VERSION = "12.64.1.1"

/**
 * 这个版本的推荐页逻辑：
 * - query 为空：走官方推荐接口 /c/f/excellent/personalized
 * - query 不为空：走你已经接通的 /mo/q/search/thread 全贴吧搜索
 *
 * 说明：
 * 1. 推荐接口这里用的是 tiebalite 同样使用的官方 JSON 推荐接口，
 *    这样能在你当前 TinyBar 结构下更稳定地落地。
 * 2. 搜索接口保持你现在已经跑通的 hybrid 搜索链路。
 */
class RealTiebaDataSource(
    private val http: TiebaHttpClient = TiebaHttpClient()
) : TiebaDataSource {

    /**
     * 官方推荐接口 host。
     * 注意：需要在 network_security_config.xml 里额外放行 c.tieba.baidu.com
     */
    private val officialPersonalizedHost = "http://c.tieba.baidu.com"

    /**
     * 为了尽量贴近官方客户端请求，准备一个本地 OkHttpClient。
     * 不复用 TiebaHttpClient 的 postSignedForm，
     * 因为它当前默认 host 是 tiebac.baidu.com，而推荐接口在 c.tieba.baidu.com。
     */
    private val officialClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * 简单生成一个会话级 CUID，避免每次都不同。
     */
    private val sessionCuid: String by lazy {
        UUID.randomUUID().toString().replace("-", "")
    }

    override suspend fun getRecommendedFeed(query: String, page: Int): FeedPage {
        return if (query.isBlank()) {
            getOfficialPersonalizedFeed(page = page)
        } else {
            searchThreadsByHybridApi(
                keyword = query,
                page = page
            )
        }
    }

    override suspend fun getForumPage(forumName: String, page: Int): ForumPage {
        val common = CommonReq.newBuilder()
            .setClientType(2)
            .setClientVersion(MAIN_VERSION)
            .build()

        val data = FrsPageReqIdl.DataReq.newBuilder()
            .setCommon(common)
            .setKw(forumName)
            .setPn(if (page == 1) 0 else page)
            .setRn(PAGE_SIZE)
            .setRnNeed(PAGE_SIZE + 5)
            .setIsGood(0)
            .setSortType(0)
            .build()

        val req = FrsPageReqIdl.newBuilder()
            .setData(data)
            .build()

        val bytes = http.postProto(
            path = "/c/f/frs/page",
            cmd = 301001,
            bytes = req.toByteArray()
        )

        val res = FrsPageResIdl.parseFrom(bytes)
        if (res.error.errorno != 0) {
            throw IllegalStateException(
                "Tieba error ${res.error.errorno}: ${res.error.errmsg}"
            )
        }

        val forum = runCatching {
            val f = res.data.forum
            ForumInfo(
                fid = if (f.id != 0L) f.id.toString() else "",
                name = f.name.ifBlank { forumName },
                slogan = "欢迎来到 ${forumName} 吧",
                memberCount = f.memberNum,
                postCount = f.postNum,
                threadCount = f.threadNum
            )
        }.getOrNull()

        val threads = res.data.threadListList.map { thread ->
            ThreadSummary(
                tid = thread.id.toString(),
                title = thread.title.ifBlank { "（无标题）" },
                author = userName(thread.author),
                replyCount = thread.replyNum,
                lastReplyTimeText = epochSecondsToText(thread.lastTimeInt.toLong()),
                forumName = forumName,
                excerpt = ""
            )
        }

        return ForumPage(
            forumInfo = forum,
            threads = threads
        )
    }

    override suspend fun getPosts(tid: String, page: Int): List<PostItem> {
        val common = CommonReq.newBuilder()
            .setClientType(2)
            .setClientVersion(MAIN_VERSION)
            .build()

        val data = PbPageReqIdl.DataReq.newBuilder()
            .setCommon(common)
            .setKz(tid.toLong())
            .setPn(page)
            .setRn(30)
            .setR(0)
            .setLz(0)
            .setWithFloor(0)
            .build()

        val req = PbPageReqIdl.newBuilder()
            .setData(data)
            .build()

        val bytes = http.postProto(
            path = "/c/f/pb/page",
            cmd = 302001,
            bytes = req.toByteArray()
        )

        val res = PbPageResIdl.parseFrom(bytes)
        if (res.error.errorno != 0) {
            throw IllegalStateException(
                "Tieba error ${res.error.errorno}: ${res.error.errmsg}"
            )
        }

        return res.data.postListList.map { post ->
            post.toUiModel()
        }
    }

    /**
     * 官方推荐接口：
     * POST http://c.tieba.baidu.com/c/f/excellent/personalized
     *
     * 这里按 tiebalite 的官方推荐参数组织方式来发：
     * - load_type: 1 首次刷新，2 加载更多
     * - pn: 页码
     * - page_thread_count: 请求条数
     * - q_type / need_forumlist / new_net_type / new_install 等保留默认值
     *
     * 返回是 JSON，不是 protobuf，所以这里用 JSONObject 解析。
     */
    private suspend fun getOfficialPersonalizedFeed(page: Int): FeedPage {
        val loadType = if (page <= 1) 1 else 2
        val responseText = postOfficialPersonalized(
            loadType = loadType,
            page = page
        )

        val root = JSONObject(responseText)
        val errorCode = root.optString("error_code").ifBlank { "0" }
        if (errorCode != "0") {
            throw IllegalStateException(
                "Tieba error $errorCode: ${root.optString("error_msg")}"
            )
        }

        val threadList = root.optJSONArray("thread_list") ?: JSONArray()
        val threads = buildList {
            for (i in 0 until threadList.length()) {
                val item = threadList.optJSONObject(i) ?: continue

                add(
                    ThreadSummary(
                        tid = item.optString("tid").ifBlank { item.optString("id") },
                        title = item.optString("title").ifBlank { "（无标题）" },
                        author = parseAuthorName(item.optJSONObject("author")),
                        replyCount = item.optString("reply_num").toIntOrNull() ?: 0,
                        lastReplyTimeText = parseLastTime(item),
                        forumName = item.optString("fname").ifBlank { "推荐" },
                        excerpt = parsePersonalizedExcerpt(item),
                        avatarUrl = parseAvatarUrl(item.optJSONObject("author")),
                        imageUrls = parseImageUrls(item)
                    )
                )
            }
        }.filter { it.tid.isNotBlank() }
            .distinctBy { it.tid }

        val pageInfo = root.optJSONObject("page_info") ?: root.optJSONObject("page")
        val hasMore = when {
            pageInfo?.has("has_more") == true -> pageInfo.optInt("has_more", 0) == 1
            pageInfo?.has("hasMore") == true -> pageInfo.optInt("hasMore", 0) == 1
            else -> threads.size >= 10
        }

        return FeedPage(
            threads = threads,
            hasMore = hasMore
        )
    }

    /**
     * 保留你现在已经接通的全贴吧搜索：
     * /mo/q/search/thread
     */
    private suspend fun searchThreadsByHybridApi(
        keyword: String,
        page: Int
    ): FeedPage {
        val encodedKeyword = java.net.URLEncoder.encode(keyword, "UTF-8")
        val referer = buildHybridSearchReferer(keyword)

        val url = buildString {
            append("https://tieba.baidu.com/mo/q/search/thread")
            append("?word=").append(encodedKeyword)
            append("&pn=").append(page)
            append("&st=0")
            append("&tt=1")
            append("&rn=").append(PAGE_SIZE)
            append("&ct=1")
            append("&is_use_zonghe=1")
            append("&cv=99.9.101")
        }

        val text = http.getText(
            url = url,
            headers = mapOf(
                "Referer" to referer,
                "Accept" to "application/json, text/plain, */*"
            )
        )

        val root = JSONObject(text)
        val errorCode = root.optInt("no", 0)
        if (errorCode != 0) {
            throw IllegalStateException(
                "Tieba error $errorCode: ${root.optString("error")}"
            )
        }

        val data = root.optJSONObject("data")
            ?: throw IllegalStateException("搜索结果 data 为空")

        val hasMore = data.optInt("has_more", 0) == 1
        val postList = data.optJSONArray("post_list")

        val threads = buildList {
            if (postList != null) {
                for (i in 0 until postList.length()) {
                    val item = postList.optJSONObject(i) ?: continue
                    val user = item.optJSONObject("user")

                    add(
                        ThreadSummary(
                            tid = item.optString("tid"),
                            title = item.optString("title").ifBlank { "（无标题）" },
                            author = user?.optString("show_nickname")
                                .orEmpty()
                                .ifBlank { user?.optString("user_name").orEmpty() }
                                .ifBlank { "未知用户" },
                            replyCount = item.optString("post_num").toIntOrNull() ?: 0,
                            lastReplyTimeText = item.optString("time").ifBlank { "-" },
                            forumName = item.optString("forum_name").ifBlank { "未知吧" },
                            excerpt = cleanSearchExcerpt(item.optString("content")),
                            avatarUrl = parseAvatarUrl(user),
                            imageUrls = parseImageUrls(item)
                        )
                    )
                }
            }
        }.distinctBy { it.tid }

        return FeedPage(
            threads = threads,
            hasMore = hasMore
        )
    }

    private fun parseAvatarUrl(author: JSONObject?): String {
        if (author == null) return ""

        val direct = listOf(
            "avatar",
            "avatar_url",
            "portrait_url",
            "user_portrait",
            "icon"
        ).firstNotNullOfOrNull { key ->
            author.optString(key).takeIf { it.isNotBlank() && it.startsWith("http") }
        }
        if (!direct.isNullOrBlank()) return direct

        val portrait = author.optString("portrait")
        if (portrait.isNotBlank()) {
            return "https://tb.himg.baidu.com/sys/portrait/item/$portrait"
        }

        return ""
    }

    private fun parseImageUrls(item: JSONObject): List<String> {
        val results = linkedSetOf<String>()
        collectImageUrls(item, results)
        return results.take(2)
    }

    private fun collectImageUrls(any: Any?, out: MutableSet<String>) {
        when (any) {
            is JSONObject -> {
                val iterator = any.keys()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    val value = any.opt(key)

                    if (value is String && looksLikeImageUrl(key, value)) {
                        out.add(normalizeImageUrl(value))
                    } else {
                        collectImageUrls(value, out)
                    }
                }
            }

            is JSONArray -> {
                for (i in 0 until any.length()) {
                    collectImageUrls(any.opt(i), out)
                }
            }

            is String -> {
                if (looksLikeImageUrl("", any)) {
                    out.add(normalizeImageUrl(any))
                }
            }
        }
    }

    private fun looksLikeImageUrl(key: String, value: String): Boolean {
        if (value.isBlank()) return false

        val lowerKey = key.lowercase(Locale.ROOT)
        val lowerValue = value.lowercase(Locale.ROOT)

        val keyLikeImage = listOf(
            "img", "image", "src", "pic", "cover", "thumbnail", "small", "medium", "big"
        ).any { lowerKey.contains(it) }

        val valueLikeImage = lowerValue.startsWith("http") && (
                lowerValue.contains(".jpg") ||
                        lowerValue.contains(".jpeg") ||
                        lowerValue.contains(".png") ||
                        lowerValue.contains(".webp") ||
                        lowerValue.contains(".gif") ||
                        lowerValue.contains("tbimg") ||
                        lowerValue.contains("imgsa.baidu") ||
                        lowerValue.contains("hiphotos") ||
                        lowerValue.contains("tiebapic")
                )

        return keyLikeImage || valueLikeImage
    }

    private fun normalizeImageUrl(url: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            else -> url
        }
    }

    /**
     * 官方推荐 form 提交。
     *
     * 这里不复用 TiebaHttpClient.postSignedForm，
     * 因为推荐接口 host 是 c.tieba.baidu.com。
     */
    private suspend fun postOfficialPersonalized(
        loadType: Int,
        page: Int
    ): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()

        val rawParams: Map<String, Any> = mapOf(
            "load_type" to loadType,
            "pn" to page,
            "_client_version" to "12.25.1.0",
            "cuid_gid" to "",
            "need_tags" to 0,
            "page_thread_count" to 15,
            "pre_ad_thread_count" to 0,
            "sug_count" to 0,
            "tag_code" to 0,
            "q_type" to 1,
            "need_forumlist" to 0,
            "new_net_type" to 1,
            "new_install" to 0,
            "request_time" to now,
            "invoke_source" to "",
            "scr_dip" to "3.0",
            "scr_h" to "2400",
            "scr_w" to "1080",
            "from" to "tieba",
            "client_type" to 2,
            "cuid" to sessionCuid,
            "cuid_galaxy2" to sessionCuid,
            "cuid_galaxy3" to "",
            "brand" to Build.BRAND,
            "model" to Build.MODEL,
            "cmode" to 1,
            "framework_ver" to "3340042",
            "is_teenager" to 0,
            "sdk_ver" to "2.34.0",
            "start_type" to 1,
            "active_timestamp" to now
        )

        val signedParams = TiebaSignUtil.sign(rawParams)

        val body = FormBody.Builder().apply {
            signedParams.forEach { (k, v) ->
                add(k, v)
            }
        }.build()

        val request = Request.Builder()
            .url("$officialPersonalizedHost/c/f/excellent/personalized")
            .post(body)
            .header("User-Agent", "bdtb for Android 12.25.1.0")
            .header("Host", "c.tieba.baidu.com")
            .header("Connection", "keep-alive")
            .header("client_type", "2")
            .header("charset", "UTF-8")
            .header("client_user_token", "")
            .header(
                "Cookie",
                "CUID=$sessionCuid;ka=open;TBBRAND=${Build.MODEL};"
            )
            .build()

        officialClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code}: ${resp.message}")
            }
            resp.body?.string() ?: throw IOException("Empty body")
        }
    }

    private fun parseAuthorName(author: JSONObject?): String {
        if (author == null) return "未知用户"

        return author.optString("name_show")
            .ifBlank { author.optString("name") }
            .ifBlank { "未知用户" }
    }

    private fun parseLastTime(item: JSONObject): String {
        val lastTimeInt = item.optString("last_time_int").toLongOrNull()
            ?: item.optLong("last_time_int", 0L)

        return if (lastTimeInt > 0L) {
            epochSecondsToText(lastTimeInt)
        } else {
            item.optString("last_time").ifBlank { "-" }
        }
    }

    private fun parsePersonalizedExcerpt(item: JSONObject): String {
        val abstractArray = item.optJSONArray("abstract")
        if (abstractArray != null) {
            val text = extractAbstractText(abstractArray)
            if (text.isNotBlank()) return text
        }

        val mediaHint = when {
            item.has("video_info") -> "[视频内容]"
            else -> ""
        }

        return mediaHint
    }

    private fun extractAbstractText(array: JSONArray): String {
        val parts = mutableListOf<String>()

        for (i in 0 until array.length()) {
            val any = array.opt(i)
            when (any) {
                is JSONObject -> {
                    val text = any.optString("text")
                        .ifBlank { any.optString("content") }
                        .ifBlank { any.optString("abstract_text") }
                    if (text.isNotBlank()) {
                        parts.add(text)
                    }
                }

                is String -> {
                    if (any.isNotBlank()) parts.add(any)
                }
            }
        }

        return parts.joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun cleanSearchExcerpt(content: String): String {
        return content
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun buildHybridSearchReferer(keyword: String): String {
        val raw = "https://tieba.baidu.com/mo/q/hybrid/search?keyword=$keyword&_webview_time=${System.currentTimeMillis()}"
        return java.net.URLEncoder.encode(raw, "UTF-8")
    }

    private fun Post.toUiModel(): PostItem {
        return PostItem(
            pid = id.toString(),
            floor = floor,
            author = userName(author),
            publishTimeText = epochSecondsToText(time.toLong()),
            content = pbContentsToText(contentList),
            comments = emptyList()
        )
    }

    private fun userName(user: User): String {
        return when {
            user.nameShow.isNotBlank() -> user.nameShow
            user.name.isNotBlank() -> user.name
            else -> "未知用户"
        }
    }

    private fun pbContentsToText(list: List<PbContent>): String {
        val text = buildString {
            list.forEach { item ->
                when {
                    item.text.isNotBlank() -> append(item.text)
                    item.link.isNotBlank() -> append(item.link)
                    item.src.isNotBlank() || item.bigCdnSrc.isNotBlank() -> append("[图片]")
                    item.voiceMd5.isNotBlank() -> append("[语音]")
                    item.hasItem() && item.item.itemName.isNotBlank() -> append("[${item.item.itemName}]")
                }
            }
        }.trim()

        return if (text.isBlank()) "（正文为空或为非文本内容）" else text
    }

    private fun epochSecondsToText(seconds: Long): String {
        if (seconds <= 0L) return "-"
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        sdf.timeZone = TimeZone.getDefault()
        return sdf.format(Date(seconds * 1000))
    }
}