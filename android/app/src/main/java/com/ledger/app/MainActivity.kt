package com.ledger.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ledger.app.data.TransactionDto
import com.ledger.app.ui.AccountsScreen
import com.ledger.app.ui.CycleInsights
import com.ledger.app.ui.PlanScreen
import com.ledger.app.ui.YearInsights
import com.ledger.app.ui.AddTransactionScreen
import com.ledger.app.ui.LoginScreen
import com.ledger.app.ui.SettingsScreen
import com.ledger.app.ui.StatisticsScreen
import com.ledger.app.ui.TransactionListScreen
import com.ledger.app.ui.theme.LedgerTheme
import com.ledger.app.ui.DetailScreen
import com.ledger.app.ui.TripsScreen
import com.ledger.app.viewmodel.TripsViewModel
import com.ledger.app.viewmodel.AccountsViewModel
import com.ledger.app.viewmodel.DetailViewModel
import com.ledger.app.viewmodel.ReportViewModel
import com.ledger.app.viewmodel.InsightsViewModel
import com.ledger.app.viewmodel.PlanViewModel
import com.ledger.app.viewmodel.AuthViewModel
import com.ledger.app.viewmodel.TransactionViewModel
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.ledger.app.ui.theme.ThemeState.mode = com.ledger.app.data.Prefs.getThemeMode(this)
        // a cache written by an older version may lack fields the new screens expect: start fresh after every update
        com.ledger.app.data.Prefs.clearCacheOnUpdate(this)
        com.ledger.app.capture.MaintenanceWorker.schedule(this)
        com.ledger.app.capture.MaintenanceWorker.runNow(this)
        setContent {
            LedgerTheme {
                LedgerApp()
                com.ledger.app.ui.UpdateCheckOnStart()
            }
        }
    }
}

@Composable
fun LedgerApp() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val authViewModel: AuthViewModel = viewModel()
    val transactionViewModel: TransactionViewModel = viewModel()
    val accountsViewModel: AccountsViewModel = viewModel()
    val planViewModel: PlanViewModel = viewModel()
    val detailViewModel: DetailViewModel = viewModel()
    val tripsViewModel: TripsViewModel = viewModel()
    val tripsState by tripsViewModel.state.collectAsState()
    val detailState by detailViewModel.state.collectAsState()
    val reportViewModel: ReportViewModel = viewModel()
    val reportState by reportViewModel.state.collectAsState()
    val planState by planViewModel.state.collectAsState()
    val insightsViewModel: InsightsViewModel = viewModel()
    val carViewModel: com.ledger.app.viewmodel.CarViewModel = viewModel()
    val carState by carViewModel.state.collectAsState()
    val insightsState by insightsViewModel.state.collectAsState()
    val accountsState by accountsViewModel.state.collectAsState()

    val authState by authViewModel.state.collectAsState()
    val txState by transactionViewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // warm every tab at start: the screens read these view models / the disk cache, so nothing waits for the network on first open
    val signedIn = authState.loggedIn || com.ledger.app.data.Prefs.isLoggedIn(context)
    androidx.compose.runtime.LaunchedEffect(signedIn) {
        if (!signedIn) return@LaunchedEffect
        insightsViewModel.loadCycle(); insightsViewModel.loadYear(); planViewModel.load(); accountsViewModel.refresh()
        carViewModel.load(); reportViewModel.load(); tripsViewModel.load()
        try {
            val care = com.ledger.app.data.ApiClient.create(context).carCare()
            com.ledger.app.data.DiskCache.put(context, "care", care)
        } catch (_: Exception) {
        }
    }

    var isListening by remember { mutableStateOf(false) }
    var editingTransaction by remember { mutableStateOf<TransactionDto?>(null) }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isListening = false
        if (result.resultCode == Activity.RESULT_OK) {
            val text = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) transactionViewModel.voiceQuickAdd(text)
        }
    }

    fun launchSpeechRecognizer() {
        isListening = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.SIMPLIFIED_CHINESE)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "说说你花了多少钱, 干什么了")
        }
        speechLauncher.launch(intent)
    }

    val recordAudioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) launchSpeechRecognizer() }

    fun triggerVoice() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            launchSpeechRecognizer()
        }
    }

    LaunchedEffect(txState.message, txState.error) {
        val text = txState.message ?: txState.error
        if (text != null) {
            snackbarHostState.showSnackbar(text)
            transactionViewModel.consumeMessage()
        }
    }

    LaunchedEffect(authState.loggedIn) {
        if (authState.loggedIn) transactionViewModel.refresh()
    }

    LaunchedEffect(Unit) {
        com.ledger.app.ui.CategoryHub.onChanged = { transactionViewModel.reloadCategories(); detailViewModel.reloadCategories() }
    }

    // reading texts was granted earlier; receiving them live is the same permission group, so the system just grants it
    val receiveSms = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(authState.loggedIn) {
        if (authState.loggedIn && com.ledger.app.capture.SmsInboxScanner.hasPermission(context) &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECEIVE_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            receiveSms.launch(android.Manifest.permission.RECEIVE_SMS)
        }
    }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner, authState.loggedIn) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && authState.loggedIn) transactionViewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } }) { padding ->
        NavHost(
            navController = navController,
            startDestination = if (authState.loggedIn) "home" else "login",
            modifier = Modifier.padding(padding)
        ) {
            composable("login") {
                LoginScreen(
                    state = authState,
                    initialBaseUrl = authViewModel.getBaseUrl(),
                    onLogin = authViewModel::login,
                    onRegister = authViewModel::register,
                    onJoin = authViewModel::join,
                    onBaseUrlChange = authViewModel::setBaseUrl
                )
                LaunchedEffect(authState.loggedIn) {
                    if (authState.loggedIn) {
                        navController.navigate("home") {
                            popUpTo("login") { inclusive = true }
                        }
                    }
                }
            }
            composable("home") {
                var selectedTab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
                androidx.activity.compose.BackHandler(enabled = detailState.request != null) { detailViewModel.close() }
                if (detailState.request != null) {
                    DetailScreen(
                        state = detailState, onBack = { detailViewModel.close() }, onSort = detailViewModel::setSort,
                        onLoadMore = detailViewModel::loadMore, onSetCategory = detailViewModel::setCategory,
                        onSubFilter = detailViewModel::setSubFilter, onDeleteTransaction = detailViewModel::deleteTransaction
                    )
                    return@composable
                }
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                icon = { Icon(Icons.Filled.Receipt, contentDescription = null) },
                                label = { Text("记账") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Icon(Icons.Filled.PieChart, contentDescription = null) },
                                label = { Text("统计") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 4,
                                onClick = { selectedTab = 4 },
                                icon = { Icon(Icons.Filled.Savings, contentDescription = null) },
                                label = { Text("计划") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 3,
                                onClick = { selectedTab = 3 },
                                icon = { Icon(Icons.Filled.AccountBalanceWallet, contentDescription = null) },
                                label = { Text("资产") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                icon = { Icon(Icons.Filled.Person, contentDescription = null) },
                                label = { Text("我的") }
                            )
                        }
                    }
                ) { homePadding ->
                    Box(modifier = Modifier.padding(homePadding)) {
                        when (selectedTab) {
                            0 -> TransactionListScreen(
                                state = txState,
                                onAddManual = { editingTransaction = null; navController.navigate("add") },
                                onVoiceTap = { triggerVoice() },
                                onRefresh = { transactionViewModel.syncAndRefresh() },
                                onLoadMore = { transactionViewModel.loadMore() },
                                onFilterChange = { transactionViewModel.setFilter(it) },
                                onSetCategory = { id, cat, sub -> transactionViewModel.setCategory(id, cat, sub) },
                                onSetTrip = { tx, tripId -> transactionViewModel.setBusinessTrip(tx, tripId) },
                                onNewBusinessTrip = { tx, name, start, end, daily, commute, autoTag -> transactionViewModel.newBusinessTripWith(tx, name, start, end, daily, commute, autoTag) },
                                onEditTransaction = { tx -> editingTransaction = tx; navController.navigate("add") },
                                onDeleteTransaction = { id -> transactionViewModel.deleteTransaction(id) },
                                isListening = isListening
                            )
                            1 -> {
                                var statMode by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
                                var includeBiz by remember { mutableStateOf(com.ledger.app.data.Prefs.getIncludeBusiness(context)) }
                                var bizVersion by remember { mutableIntStateOf(0) }
                                androidx.compose.foundation.layout.Column {
                                    androidx.compose.foundation.layout.Row(
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                                            .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                                    ) {
                                        listOf("总览", "花在哪里", "出行", "用车", "保养", "全年").forEachIndexed { i, label ->
                                            androidx.compose.material3.FilterChip(
                                                selected = statMode == i, onClick = { statMode = i }, label = { Text(label) }
                                            )
                                        }
                                        androidx.compose.material3.FilterChip(
                                            selected = includeBiz,
                                            onClick = {
                                                includeBiz = !includeBiz
                                                com.ledger.app.data.Prefs.setIncludeBusiness(context, includeBiz)
                                                bizVersion++
                                                insightsViewModel.loadCycle()
                                                insightsViewModel.loadYear()
                                                transactionViewModel.refresh()
                                            },
                                            label = { Text(if (includeBiz) "含出差" else "不含出差") }
                                        )
                                    }
                                    when (statMode) {
                                        0 -> androidx.compose.runtime.key(bizVersion) { StatisticsScreen(onOpenDetail = { detailViewModel.open(it) }) }
                                        1 -> {
                                            androidx.compose.runtime.LaunchedEffect(Unit) { reportViewModel.load() }
                                            CycleInsights(
                                                insightsState, insightsViewModel::loadCycle, insightsViewModel::shiftCycle,
                                                report = reportState.report, onOpenDetail = { detailViewModel.open(it) },
                                                categories = txState.categories, onSetCategory = insightsViewModel::setCategory
                                            )
                                        }
                                        2 -> TripsScreen(
                                            tripsState, tripsViewModel::load, tripsViewModel::create, tripsViewModel::update, tripsViewModel::settle, tripsViewModel::unsettle,
                                            tripsViewModel::recentIncomes, tripsViewModel::delete, tripsViewModel::dismiss,
                                            onOpenDetail = { detailViewModel.open(it) }
                                        )
                                        3 -> com.ledger.app.ui.CarScreen(carState, carViewModel::load, carViewModel::setBucket)
                                        4 -> com.ledger.app.ui.CareScreen()
                                        else -> YearInsights(insightsState, insightsViewModel::loadYear)
                                    }
                                }
                            }
                            4 -> PlanScreen(planState, planViewModel::load, planViewModel::save, planViewModel::markFixed)
                            3 -> AccountsScreen(
                                state = accountsState,
                                onRefresh = accountsViewModel::refresh,
                                loadReceivable = accountsViewModel::receivable,
                                onAdd = accountsViewModel::add,
                                onUpdate = accountsViewModel::update
                            )
                            else -> SettingsScreen(
                                baseUrl = authViewModel.getBaseUrl(),
                                inviteCode = txState.inviteCode,
                                onLogout = {
                                    authViewModel.logout()
                                    navController.navigate("login") {
                                        popUpTo("home") { inclusive = true }
                                    }
                                }
                            )
                        }
                    }
                }
            }
            composable("add") {
                // Fixed for the lifetime of this screen: after the first save the shared state is cleared, and a quick
                // second tap on 保存 used to see "no transaction being edited" and create a duplicate record.
                val editing = remember { editingTransaction }
                var handled by remember { mutableStateOf(false) }
                AddTransactionScreen(
                    categories = txState.categories,
                    existing = editing,
                    onSave = { txType, amount, categoryId, note ->
                        if (!handled) {
                            handled = true
                            if (editing != null) {
                                transactionViewModel.updateTransaction(editing.id, txType, amount, categoryId, note)
                            } else {
                                transactionViewModel.createManual(txType, amount, categoryId, note)
                            }
                            editingTransaction = null
                            navController.popBackStack()
                        }
                    },
                    onDelete = if (editing != null) {
                        {
                            if (!handled) {
                                handled = true
                                transactionViewModel.deleteTransaction(editing.id)
                                editingTransaction = null
                                navController.popBackStack()
                            }
                        }
                    } else null
                )
            }
        }
    }
}
