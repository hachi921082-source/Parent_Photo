package com.example.parentphoto.ui.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navigation
import com.example.parentphoto.R
import kotlinx.coroutines.launch

/** 設定の画面ルート(settings-screen PLAN.md「Routes」)。 */
object SettingsRoutes {
    const val GRAPH = "settings_graph"
    const val MAIN = "settings"
    const val UNSENT = "settings/unsent"
    const val LOG = "settings/log"
}

/**
 * 隠し設定の画面遷移(settings-screen PLAN.md)。メイン画面と副画面(未送信一覧・送信ログ)を1つの入れ子グラフにまとめる。
 * 副画面から戻るとメイン画面に戻り、未保存の入力も残る(ViewModel は Activity 単位)。
 * 設定を閉じるときは ViewModel の入力を捨ててから onClose(MainViewModel.onSettingsClosed)を呼ぶ。
 * 画面の切り替えにアニメーションは付けない(Q58)。
 */
@Composable
fun SettingsNavHost(viewModel: SettingsViewModel, onClose: () -> Unit) {
    val nav = rememberNavController()
    val close: () -> Unit = {
        viewModel.onClosed()
        onClose()
    }
    // 副画面からはメイン画面まで戻す(連打でメイン画面まで消さない)
    val backToMain: () -> Unit = { nav.popBackStack(SettingsRoutes.MAIN, inclusive = false) }

    NavHost(
        navController = nav,
        startDestination = SettingsRoutes.GRAPH,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        navigation(startDestination = SettingsRoutes.MAIN, route = SettingsRoutes.GRAPH) {
            composable(SettingsRoutes.MAIN) {
                SettingsRoute(
                    viewModel = viewModel,
                    onOpenUnsent = { nav.navigate(SettingsRoutes.UNSENT) { launchSingleTop = true } },
                    onOpenLog = { nav.navigate(SettingsRoutes.LOG) { launchSingleTop = true } },
                    onClose = close,
                )
            }
            composable(SettingsRoutes.UNSENT) { UnsentRoute(viewModel, onBack = backToMain) }
            composable(SettingsRoutes.LOG) {
                val log by viewModel.sendLog.collectAsState()
                SettingsSubScreen(title = R.string.link_send_log, onBack = backToMain) { m -> SendLogList(log, m) }
            }
        }
    }
}

/**
 * 未送信一覧の副画面(ds 9.6)。「今すぐ再送」が枠待ちになったときだけ、この画面の Snackbar に
 * 「順番待ちです。しばらくお待ちください」を出す(Short・アクションなし、Q57)。
 */
@Composable
private fun UnsentRoute(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val groups by viewModel.unsent.collectAsState()
    val deleteId by viewModel.unsentDeleteId.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val waitingMessage = stringResource(R.string.snackbar_waiting)
    LaunchedEffect(viewModel) {
        viewModel.unsentWaitingEvents.collect {
            launch { snackbar.showSnackbar(waitingMessage, duration = SnackbarDuration.Short) }
        }
    }
    SettingsSubScreen(title = R.string.link_unsent, onBack = onBack, snackbarHostState = snackbar) { m ->
        UnsentList(groups, onResend = viewModel::onResendNow, onDelete = viewModel::onDeleteUnsent, modifier = m)
    }
    if (deleteId != null) {
        DeleteUnsentDialog(onConfirm = viewModel::onDeleteUnsentConfirmed, onDismiss = viewModel::onDeleteUnsentDismiss)
    }
}

/** 副画面の枠。アプリバー(画面名と戻る矢印)と、画面ごとの SnackbarHost を持つ。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSubScreen(
    @StringRes title: Int,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    content: @Composable (Modifier) -> Unit = { Box(it.fillMaxSize()) },
) {
    BackHandler(enabled = true, onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding -> content(Modifier.padding(padding)) }
}
