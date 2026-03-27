package com.example.nearchat.navigation

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.nearchat.data.datasource.LocalUserDataSource
import com.example.nearchat.data.event.HomeUiEvent
import com.example.nearchat.ui.screen.AuthScreen
import com.example.nearchat.ui.screen.ChatScreen
import com.example.nearchat.ui.screen.DeviceListScreen
import com.example.nearchat.ui.screen.HomeScreen
import com.example.nearchat.ui.viewmodel.AuthViewModel
import com.example.nearchat.ui.viewmodel.ChatViewModel
import com.example.nearchat.ui.viewmodel.DeviceListViewModel
import com.example.nearchat.ui.viewmodel.HomeViewModel

sealed class Screen(val route: String) {
    object Auth : Screen("auth")
    object Home : Screen("home")
    object DeviceList : Screen("device_list")
    object Chat : Screen("chat")
    object Profile : Screen("profile")
    object GroupLobby : Screen("group_lobby")
    object GroupChat : Screen("group_chat")
}

sealed class UiEffect {
    data class NavigateTo(val screen: Screen) : UiEffect()
    object NavigateBack : UiEffect()
    data class ShowSnackbar(val message: String) : UiEffect()
    data class ShowDialog(val message: String) : UiEffect()
    object TriggerHaptic : UiEffect()
    object RequestDiscoverable : UiEffect()
}

@Composable
fun AppNavigation(
    localUserDataSource: LocalUserDataSource,
    navController: NavHostController = rememberNavController()
) {
    // Dynamic start destination — auth if not logged in, home if session exists
    val startDestination = if (localUserDataSource.isLoggedIn()) {
        Screen.Home.route
    } else {
        Screen.Auth.route
    }

    // Shared HomeViewModel scoped to the Activity
    val homeViewModel: HomeViewModel = hiltViewModel()
    val homeState by homeViewModel.state.collectAsState()

    // Collect HomeViewModel navigation effects at the top level
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        homeViewModel.effect.collect { effect ->
            when (effect) {
                is UiEffect.NavigateTo -> {
                    navController.navigate(effect.screen.route) {
                        if (effect.screen == Screen.Chat) {
                            popUpTo(Screen.Home.route) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                }
                is UiEffect.NavigateBack -> {
                    navController.popBackStack(Screen.Home.route, false)
                }
                is UiEffect.RequestDiscoverable -> {
                    val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                        putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                    }
                    context.startActivity(discoverableIntent)
                }
                else -> {}
            }
        }
    }

    // Global incoming connection request dialog — shows on ANY screen
    if (homeState.incomingRequest != null) {
        AlertDialog(
            onDismissRequest = { homeViewModel.onEvent(HomeUiEvent.DeclineConnection) },
            title = { Text("Incoming Connection") },
            text = {
                Text("${homeState.incomingRequest} wants to connect with you.")
            },
            confirmButton = {
                TextButton(onClick = { homeViewModel.onEvent(HomeUiEvent.AcceptConnection) }) {
                    Text("Accept")
                }
            },
            dismissButton = {
                TextButton(onClick = { homeViewModel.onEvent(HomeUiEvent.DeclineConnection) }) {
                    Text("Decline")
                }
            }
        )
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Auth.route) {
            val viewModel: AuthViewModel = hiltViewModel()
            val state by viewModel.state.collectAsState()

            LaunchedEffect(Unit) {
                viewModel.effect.collect { effect ->
                    when (effect) {
                        is UiEffect.NavigateTo -> {
                            navController.navigate(effect.screen.route) {
                                popUpTo(Screen.Auth.route) { inclusive = true }
                            }
                        }
                        else -> {}
                    }
                }
            }

            AuthScreen(
                state = state,
                onEvent = viewModel::onEvent
            )
        }

        composable(Screen.Home.route) {
            HomeScreen(
                state = homeState,
                onEvent = homeViewModel::onEvent
            )
        }

        composable(Screen.DeviceList.route) {
            val viewModel: DeviceListViewModel = hiltViewModel()
            val state by viewModel.state.collectAsState()

            LaunchedEffect(Unit) {
                viewModel.effect.collect { effect ->
                    when (effect) {
                        is UiEffect.NavigateTo -> {
                            navController.navigate(effect.screen.route) {
                                popUpTo(Screen.Home.route)
                            }
                        }
                        is UiEffect.NavigateBack -> {
                            navController.popBackStack()
                        }
                        else -> {}
                    }
                }
            }

            DeviceListScreen(
                state = state,
                onEvent = viewModel::onEvent
            )
        }

        composable(Screen.Chat.route) {
            val viewModel: ChatViewModel = hiltViewModel()
            val state by viewModel.state.collectAsState()

            LaunchedEffect(Unit) {
                viewModel.effect.collect { effect ->
                    when (effect) {
                        is UiEffect.NavigateTo -> {
                            navController.navigate(effect.screen.route) {
                                popUpTo(Screen.Home.route) { inclusive = false }
                                launchSingleTop = true
                            }
                        }
                        is UiEffect.NavigateBack -> {
                            navController.popBackStack(Screen.Home.route, false)
                        }
                        else -> {}
                    }
                }
            }

            ChatScreen(
                state = state,
                onEvent = viewModel::onEvent
            )
        }

        composable(Screen.Profile.route) {
            val viewModel: com.example.nearchat.ui.viewmodel.ProfileViewModel = hiltViewModel()
            val state by viewModel.state.collectAsState()

            LaunchedEffect(Unit) {
                viewModel.effect.collect { effect ->
                    when (effect) {
                        is UiEffect.NavigateBack -> navController.popBackStack()
                        is UiEffect.NavigateTo -> {
                            navController.navigate(effect.screen.route) {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                        else -> {}
                    }
                }
            }

            com.example.nearchat.ui.screen.ProfileScreen(
                state = state,
                onEvent = viewModel::onEvent
            )
        }

        composable(Screen.GroupLobby.route) {
            val viewModel: com.example.nearchat.ui.viewmodel.GroupLobbyViewModel = hiltViewModel()
            val state by viewModel.state.collectAsState()

            LaunchedEffect(Unit) {
                viewModel.effect.collect { effect ->
                    when (effect) {
                        is UiEffect.NavigateTo -> {
                            navController.navigate(effect.screen.route) {
                                popUpTo(Screen.Home.route) { inclusive = false }
                            }
                        }
                        is UiEffect.NavigateBack -> {
                            navController.popBackStack(Screen.Home.route, false)
                        }
                        else -> {}
                    }
                }
            }

            com.example.nearchat.ui.screen.GroupLobbyScreen(
                state = state,
                onEvent = viewModel::onEvent
            )
        }

        composable(Screen.GroupChat.route) {
            val viewModel: com.example.nearchat.ui.viewmodel.GroupChatViewModel = hiltViewModel()
            val state by viewModel.state.collectAsState()

            LaunchedEffect(Unit) {
                viewModel.effect.collect { effect ->
                    when (effect) {
                        is UiEffect.NavigateTo -> {
                            navController.navigate(effect.screen.route) {
                                popUpTo(Screen.Home.route) { inclusive = false }
                            }
                        }
                        is UiEffect.NavigateBack -> {
                            navController.popBackStack(Screen.Home.route, false)
                        }
                        else -> {}
                    }
                }
            }

            com.example.nearchat.ui.screen.GroupChatScreen(
                state = state,
                onEvent = viewModel::onEvent
            )
        }
    }
}
