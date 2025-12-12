package com.gorunjinian.metrovault.feature.home

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavHostController
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.data.repository.UserPreferencesRepository
import com.gorunjinian.metrovault.navigation.Screen
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.feature.settings.SettingsContent
import com.gorunjinian.metrovault.feature.wallet.list.WalletsListContent

enum class BottomNavTab {
    WALLETS, SETTINGS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavHostController,
    wallet: Wallet,
    secureStorage: SecureStorage,
    userPreferencesRepository: UserPreferencesRepository,
    activity: androidx.fragment.app.FragmentActivity?,
    onCompleteMnemonic: () -> Unit
) {
    var selectedTab by rememberSaveable { mutableStateOf(BottomNavTab.WALLETS) }
    var showCreateDialog by remember { mutableStateOf(false) }

    val wallets by wallet.wallets.collectAsState()

    // FIX: Observe loading state for UI feedback (Bug #3 & #4)
    val isLoading by wallet.isLoading.collectAsState()

    val pagerState = rememberPagerState(
        pageCount = { 2 },
        initialPage = when (selectedTab) {
            BottomNavTab.WALLETS -> 0
            BottomNavTab.SETTINGS -> 1
        }
    )

    // Coroutine scope for tab navigation
    val scope = rememberCoroutineScope()

    // OPTIMIZATION: Cache lambda callbacks to prevent recreation during swipe
    // These lambdas capture stable references and don't change during normal operation
    val onWalletClickCallback = remember<(String) -> Unit>(wallet, navController, scope) {
        { walletId ->
            scope.launch {
                val loaded = wallet.openWallet(walletId)
                if (loaded) {
                    navController.navigate(Screen.WalletDetails.route)
                }
            }
        }
    }

    val onViewAddressesCallback = remember<(String) -> Unit>(wallet, navController, scope) {
        { walletId ->
            scope.launch {
                val loaded = wallet.openWallet(walletId)
                if (loaded) {
                    navController.navigate(Screen.Addresses.route)
                }
            }
        }
    }

    val onScanPSBTCallback = remember<(String) -> Unit>(wallet, navController, scope) {
        { walletId ->
            scope.launch {
                val loaded = wallet.openWallet(walletId)
                if (loaded) {
                    navController.navigate(Screen.ScanPSBT.route)
                }
            }
        }
    }

    val onCheckAddressCallback = remember<(String) -> Unit>(wallet, navController, scope) {
        { walletId ->
            scope.launch {
                val loaded = wallet.openWallet(walletId)
                if (loaded) {
                    navController.navigate(Screen.CheckAddress.route)
                }
            }
        }
    }

    // Sync pager to selectedTab on configuration change/rotation
    // This ensures the pager shows the correct page even if rememberPagerState
    // couldn't restore its state properly
    LaunchedEffect(Unit) {
        val expectedPage = when (selectedTab) {
            BottomNavTab.WALLETS -> 0
            BottomNavTab.SETTINGS -> 1
        }
        if (pagerState.currentPage != expectedPage) {
            pagerState.scrollToPage(expectedPage)
        }
    }

    // Sync tab selection with pager - use settledPage to avoid recomposition during swipe
    // settledPage only updates after the swipe animation completes, preventing mid-animation jank
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val targetTab = when (page) {
                0 -> BottomNavTab.WALLETS
                1 -> BottomNavTab.SETTINGS
                else -> BottomNavTab.WALLETS
            }
            if (selectedTab != targetTab) {
                selectedTab = targetTab
            }
        }
    }

    // Show loading dialog when wallet is being opened
    if (isLoading) {
        LoadingDialog()
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "Metro Vault",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        navController.navigate(Screen.About.route)
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_info),
                            contentDescription = "About"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        wallet.emergencyWipe()
                        navController.navigate(Screen.Unlock.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_lock),
                            contentDescription = "Lock"
                        )
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // Wallets Tab
                NavigationBarItem(
                    selected = selectedTab == BottomNavTab.WALLETS,
                    onClick = {
                        if (pagerState.currentPage != 0) {
                            selectedTab = BottomNavTab.WALLETS  // Immediate highlight
                            scope.launch { pagerState.animateScrollToPage(0) }
                        }
                    },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_wallet),
                            contentDescription = "Wallets"
                        )
                    },
                    label = { Text("Wallets") }
                )

                // Center spacer for FAB
                Spacer(modifier = Modifier.weight(1f))

                // Settings Tab
                NavigationBarItem(
                    selected = selectedTab == BottomNavTab.SETTINGS,
                    onClick = {
                        if (pagerState.currentPage != 1) {
                            selectedTab = BottomNavTab.SETTINGS  // Immediate highlight
                            scope.launch { pagerState.animateScrollToPage(1) }
                        }
                    },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = "Settings"
                        )
                    },
                    label = { Text("Settings") }
                )
            }
        },
        floatingActionButton = {
            val maxWallets = if (wallet.isDecoyMode) Wallet.MAX_DECOY_WALLETS else Wallet.MAX_MAIN_WALLETS
            val showFab = wallets.size < maxWallets && pagerState.currentPage == 0
            androidx.compose.animation.AnimatedVisibility(
                visible = showFab,
                enter = androidx.compose.animation.scaleIn(),
                exit = androidx.compose.animation.scaleOut()
            ) {
                ExtendedFloatingActionButton(
                    onClick = { showCreateDialog = true },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_add),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    text = { Text("Add Wallet") }
                )
            }
        },
        floatingActionButtonPosition = FabPosition.End
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                key = { it },
                beyondViewportPageCount = 1, // Pre-render adjacent page for smoother swiping
                flingBehavior = PagerDefaults.flingBehavior(
                    state = pagerState,
                    snapAnimationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                )
            ) { page ->
                when (page) {
                    0 -> WalletsListContent(
                        wallet = wallet,
                        secureStorage = secureStorage,
                        autoExpandSingleWallet = userPreferencesRepository.autoExpandSingleWallet.collectAsState().value,
                        onWalletClick = onWalletClickCallback,
                        onViewAddresses = onViewAddressesCallback,
                        onScanPSBT = onScanPSBTCallback,
                        onCheckAddress = onCheckAddressCallback
                    )
                    1 -> SettingsContent(
                        wallet = wallet,
                        secureStorage = secureStorage,
                        userPreferencesRepository = userPreferencesRepository,
                        activity = activity,
                        onCompleteMnemonic = onCompleteMnemonic
                    )
                }
            }
        }
    }

    // Create Wallet Dialog
    if (showCreateDialog) {
        val maxWallets = if (wallet.isDecoyMode) Wallet.MAX_DECOY_WALLETS else Wallet.MAX_MAIN_WALLETS
        val currentCount = wallets.size

        AlertDialog(
            onDismissRequest = {
                @Suppress("AssignedValueIsNeverRead")
                showCreateDialog = false
            },
            title = { Text("Add Wallet") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("How would you like to add a wallet?")
                    Text(
                        text = "Current: $currentCount/$maxWallets wallets",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        @Suppress("AssignedValueIsNeverRead")
                        showCreateDialog = false
                        navController.navigate(Screen.CreateWallet.route)
                    }
                ) {
                    Text("Create New")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        @Suppress("AssignedValueIsNeverRead")
                        showCreateDialog = false
                        navController.navigate(Screen.ImportWallet.route)
                    }
                ) {
                    Text("Import Existing")
                }
            }
        )
    }
}

/**
 * Loading dialog shown while wallet is being decrypted and loaded.
 * Provides visual feedback during the PBKDF2 key derivation process.
 */
@Composable
fun LoadingDialog() {
    Dialog(
        onDismissRequest = { /* Cannot dismiss */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Card(
            modifier = Modifier
                .padding(16.dp),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Loading wallet...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}