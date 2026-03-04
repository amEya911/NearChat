package com.example.nearchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.nearchat.data.datasource.LocalUserDataSource
import com.example.nearchat.navigation.AppNavigation
import com.example.nearchat.ui.theme.NearChatTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var localUserDataSource: LocalUserDataSource

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NearChatTheme {
                AppNavigation(localUserDataSource = localUserDataSource)
            }
        }
    }
}