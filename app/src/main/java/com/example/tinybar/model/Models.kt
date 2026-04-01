package com.example.tinybar.model

data class ForumInfo(
    val fid: String,
    val name: String,
    val slogan: String,
    val memberCount: Int,
    val postCount: Int,
    val threadCount: Int
)

data class ThreadSummary(
    val tid: String,
    val title: String,
    val author: String,
    val replyCount: Int,
    val lastReplyTimeText: String,
    val forumName: String,
    val excerpt: String = ""
)

data class CommentItem(
    val id: String,
    val author: String,
    val content: String
)

data class PostItem(
    val pid: String,
    val floor: Int,
    val author: String,
    val publishTimeText: String,
    val content: String,
    val comments: List<CommentItem> = emptyList()
)