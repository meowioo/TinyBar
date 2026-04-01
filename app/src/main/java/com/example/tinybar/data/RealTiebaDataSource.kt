package com.example.tinybar.data

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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class RealTiebaDataSource(
    private val http: TiebaHttpClient = TiebaHttpClient()
) : TiebaDataSource {

    override suspend fun getForumPage(forumName: String, page: Int): ForumPage {
        val common = CommonReq.newBuilder()
            .setClientType(2)
            .setClientVersion("12.64.1.1")
            .build()

        val data = FrsPageReqIdl.DataReq.newBuilder()
            .setCommon(common)
            .setKw(forumName)
            .setPn(if (page == 1) 0 else page)
            .setRn(30)
            .setRnNeed(35)
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
                fid = when {
                    f.id != 0L -> f.id.toString()
                    else -> ""
                },
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
                lastReplyTimeText = epochSecondsToText(thread.lastTimeInt.toLong())
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
            .setClientVersion("12.64.1.1")
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