package com.example.tinybar.tbui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.tinybar.data.TiebaRepository
import com.example.tinybar.model.PostItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ThreadDetailUiState(
    val tid: String = "",
    val title: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val posts: List<PostItem> = emptyList()
)

class ThreadDetailViewModel(
    private val repository: TiebaRepository,
    private val tid: String,
    private val title: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ThreadDetailUiState(
            tid = tid,
            title = title
        )
    )
    val uiState: StateFlow<ThreadDetailUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                errorMessage = null
            )

            runCatching {
                repository.getPosts(tid)
            }.onSuccess { posts ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    posts = posts
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = e.message ?: "加载失败"
                )
            }
        }
    }

    companion object {
        fun factory(
            repository: TiebaRepository,
            tid: String,
            title: String
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ThreadDetailViewModel(repository, tid, title)
            }
        }
    }
}