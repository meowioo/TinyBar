package com.example.tinybar.model

data class FeedPage(
    val threads: List<ThreadSummary>,
    val hasMore: Boolean
)