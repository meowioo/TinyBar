package com.example.tinybar.data

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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val PAGE_SIZE = 30
private const val MAIN_VERSION = "12.64.1.1"

class RealTiebaDataSource(
    private val http: TiebaHttpClient = TiebaHttpClient()
) : TiebaDataSource {

    private val recommendedBars = listOf(
        "原神",
        "崩坏：星穹铁道",
        "明日方舟",
        "安卓",
        "数码",
        "Jetpack Compose"
    )

    override suspend fun getRecommendedFeed(query: String, page: Int): FeedPage = coroutineScope {
        if (query.isBlank()) {
            val forumPages = recommendedBars.map { barName ->
                async {
                    runCatching { getForumPage(barName, page) }.getOrNull()
                }
            }.awaitAll().filterNotNull()

            val mergedThreads = forumPages
                .flatMap { it.threads }
                .sortedByDescending { it.replyCount }
                .distinctBy { it.tid }

            return@coroutineScope FeedPage(
                threads = mergedThreads,
                hasMore = forumPages.any { it.threads.size >= PAGE_SIZE }
            )
        }

        return@coroutineScope searchThreadsByHybridApi(
            keyword = query,
            page = page
        )
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

    private suspend fun searchThreadsByHybridApi(
        keyword: String,
        page: Int
    ): FeedPage {
        val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")
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
                            excerpt = cleanSearchExcerpt(item.optString("content"))
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

    private fun buildHybridSearchReferer(keyword: String): String {
        val raw = "https://tieba.baidu.com/mo/q/hybrid/search?keyword=$keyword&_webview_time=${System.currentTimeMillis()}"
        return URLEncoder.encode(raw, "UTF-8")
    }

    private fun cleanSearchExcerpt(content: String): String {
        return content
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
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