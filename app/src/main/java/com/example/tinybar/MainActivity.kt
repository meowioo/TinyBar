package com.example.tinybar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.tinybar.data.RealTiebaDataSource
import com.example.tinybar.data.TiebaRepository
import com.example.tinybar.tbui.TinyBarApp
import com.example.tinybar.ui.theme.TinyBarTheme

class MainActivity : ComponentActivity() {

    private val repository by lazy {
        TiebaRepository(
            dataSource = RealTiebaDataSource()
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TinyBarTheme {
                TinyBarApp(repository = repository)
            }
        }
    }
}