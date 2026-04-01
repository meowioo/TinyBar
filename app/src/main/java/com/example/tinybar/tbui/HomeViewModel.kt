package com.example.tinybar.tbui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.tinybar.data.TiebaRepository
import com.example.tinybar.model.ForumInfo
import com.example.tinybar.model.ThreadSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 30

data class HomeUiState(
    val forumNameInput: String = "原神",
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val errorMessage: String? = null,
    val forumInfo: ForumInfo? = null,
    val threads: List<ThreadSummary> = emptyList(),
    val currentPage: Int = 0,
    val hasMore: Boolean = true
)

class HomeViewModel(
    private val repository: TiebaRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun onForumNameChange(value: String) {
        _uiState.value = _uiState.value.copy(forumNameInput = value)
    }

    /**
     * 重新加载首页：
     * - 从第 1 页开始
     * - 覆盖旧数据
     */
    fun refresh() {
        loadPage(page = 1, append = false)
    }

    /**
     * 加载下一页：
     * - 只追加，不覆盖
     */
    fun loadNextPage() {
        val state = _uiState.value

        if (state.isLoading || state.isLoadingMore || !state.hasMore) {
            return
        }

        loadPage(page = state.currentPage + 1, append = true)
    }

    private fun loadPage(page: Int, append: Boolean) {
        val forumName = _uiState.value.forumNameInput.trim().ifBlank { "原神" }
        val oldState = _uiState.value

        viewModelScope.launch {
            _uiState.value = if (append) {
                oldState.copy(
                    isLoadingMore = true,
                    errorMessage = null
                )
            } else {
                oldState.copy(
                    isLoading = true,
                    isLoadingMore = false,
                    errorMessage = null,
                    hasMore = true
                )
            }

            runCatching {
                repository.getForumPage(forumName, page)
            }.onSuccess { forumPage ->
                val newThreads = forumPage.threads
                val mergedThreads = if (append) {
                    (oldState.threads + newThreads).distinctBy { it.tid }
                } else {
                    newThreads.distinctBy { it.tid }
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    errorMessage = null,
                    forumInfo = forumPage.forumInfo ?: oldState.forumInfo,
                    threads = mergedThreads,
                    currentPage = page,
                    hasMore = newThreads.size >= PAGE_SIZE
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    errorMessage = e.message ?: if (append) "加载下一页失败" else "加载失败"
                )
            }
        }
    }

    companion object {
        fun factory(repository: TiebaRepository): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    HomeViewModel(repository)
                }
            }
    }
}