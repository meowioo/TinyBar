package com.example.tinybar.model

data class ForumPage(
    val forumInfo: ForumInfo?,
    val threads: List<ThreadSummary>
)