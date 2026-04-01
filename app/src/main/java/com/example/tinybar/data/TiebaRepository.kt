package com.example.tinybar.data

import com.example.tinybar.model.FeedPage
import com.example.tinybar.model.ForumPage
import com.example.tinybar.model.PostItem

class TiebaRepository(
    private val dataSource: TiebaDataSource
) {
    suspend fun getRecommendedFeed(query: String = "", page: Int = 1): FeedPage {
        return dataSource.getRecommendedFeed(query, page)
    }

    suspend fun getForumPage(forumName: String, page: Int = 1): ForumPage {
        return dataSource.getForumPage(forumName, page)
    }

    suspend fun getPosts(tid: String, page: Int = 1): List<PostItem> {
        return dataSource.getPosts(tid, page)
    }
}