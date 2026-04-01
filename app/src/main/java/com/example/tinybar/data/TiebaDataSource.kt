package com.example.tinybar.data

import com.example.tinybar.model.ForumPage
import com.example.tinybar.model.PostItem

interface TiebaDataSource {
    suspend fun getForumPage(forumName: String, page: Int = 1): ForumPage
    suspend fun getPosts(tid: String, page: Int = 1): List<PostItem>
}