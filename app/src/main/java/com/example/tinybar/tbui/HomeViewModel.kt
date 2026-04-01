package com.example.tinybar.tbui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.tinybar.data.TiebaRepository
import com.example.tinybar.model.ThreadSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeUiState(
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val errorMessage: String? = null,
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

    fun onSearchQueryChange(value: String) {
        _uiState.value = _uiState.value.copy(searchQuery = value)
    }

    fun refresh() {
        loadPage(page = 1, append = false)
    }

    fun loadNextPage() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || !state.hasMore) return
        loadPage(page = state.currentPage + 1, append = true)
    }

    private fun loadPage(page: Int, append: Boolean) {
        val query = _uiState.value.searchQuery.trim()
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
                repository.getRecommendedFeed(query = query, page = page)
            }.onSuccess { feedPage ->
                val mergedThreads = if (append) {
                    (oldState.threads + feedPage.threads).distinctBy { it.tid }
                } else {
                    feedPage.threads.distinctBy { it.tid }
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    errorMessage = null,
                    threads = mergedThreads,
                    currentPage = page,
                    hasMore = feedPage.hasMore
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