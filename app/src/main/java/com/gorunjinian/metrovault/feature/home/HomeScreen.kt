package com.gorunjinian.metrovault.feature.home

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.togetherWith
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

@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavHostController,
    wallet: Wallet,
    secureStorage: SecureStorage,
    userPreferencesRepository: UserPreferencesRepository,
    activity: androidx.fragment.app.FragmentActivity?,
    onCompleteMnemonic: () -> Unit,
    onAppearanceSettings: () -> Unit,
    onSecuritySettings: () -> Unit,
    onAdvancedSettings: () -> Unit,
    onAbout: () -> Unit
) {
    var selectedTab by rememberSaveable { mutableStateOf(BottomNavTab.WALLETS) }
    var showCreateDialog by remember { mutableStateOf(false) }

    val wallets by wallet.wallets.collectAsState()

    // FIX: Observe loading state for UI feedback (Bug #3 & #4)
    val isLoading by wallet.isLoading.collectAsState()
    
    // Edit mode state for wallet list
    var isEditMode by remember { mutableStateOf(false) }

    val pagerState = rememberPagerState(
        pageCount = { 2 },
        initialPage = when (selectedTab) {
            BottomNavTab.WALLETS -> 0
            BottomNavTab.SETTINGS -> 1
        }
    )

    // Coroutine scope for tab navigation
    val scope = rememberCoroutineScope()
    
    // View for haptic feedback
    val view = LocalView.current

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

    val onExportCallback = remember<(String) -> Unit>(wallet, navController, scope) {
        { walletId ->
            scope.launch {
                val loaded = wallet.openWallet(walletId)
                if (loaded) {
                    navController.navigate(Screen.ExportOptions.route)
                }
            }
        }
    }

    val onBIP85Callback = remember<(String) -> Unit>(wallet, navController, scope) {
        { walletId ->
            scope.launch {
                val loaded = wallet.openWallet(walletId)
                if (loaded) {
                    navController.navigate(Screen.BIP85Derive.route)
                }
            }
        }
    }

    val onSignMessageCallback = remember<(String) -> Unit>(wallet, navController, scope) {
        { walletId ->
            scope.launch {
                val loaded = wallet.openWallet(walletId)
                if (loaded) {
                    navController.navigate(Screen.SignMessage.route)
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
    
    // Handle back gesture/button to exit edit mode
    BackHandler(enabled = isEditMode) {
        isEditMode = false
    }
    
    // Exit edit mode when switching away from Wallets tab
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != 0 && isEditMode) {
            isEditMode = false
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background, // Use normal background
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "Metro Vault",
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    // Edit button - only on Wallets tab
                    if (pagerState.currentPage == 0) {
                        IconButton(onClick = { isEditMode = !isEditMode }) {
                            Icon(
                                painter = painterResource(
                                    if (isEditMode) R.drawable.ic_check else R.drawable.ic_edit
                                ),
                                contentDescription = if (isEditMode) "Done Editing" else "Edit Wallets",
                                modifier = if (isEditMode) Modifier.size(28.dp) else Modifier
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
        // No bottomBar or floatingActionButton - we overlay them together
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            // Main content with top padding only, extending to bottom
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding()),
                key = { it },
                beyondViewportPageCount = 1,
                // Disable swiping in edit mode to prevent accidental page changes
                userScrollEnabled = !isEditMode,
                flingBehavior = PagerDefaults.flingBehavior(
                    state = pagerState,
                    snapAnimationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                )
            ) { page ->
                // Add bottom padding within each page content for scrollable area clearance
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 100.dp) // Clear space for floating nav
                ) {
                    when (page) {
                        0 -> {
                            // Filter out BIP85 from shortcuts if disabled
                            val bip85Enabled = userPreferencesRepository.bip85Enabled.collectAsState().value
                            val filteredShortcuts = userPreferencesRepository.quickShortcuts.collectAsState().value
                                .filter { bip85Enabled || it != com.gorunjinian.metrovault.data.model.QuickShortcut.BIP85 }
                            
                            WalletsListContent(
                                wallet = wallet,
                                secureStorage = secureStorage,
                                isEditMode = isEditMode,
                                autoExpandSingleWallet = userPreferencesRepository.autoExpandSingleWallet.collectAsState().value,
                                quickShortcuts = filteredShortcuts,
                                onWalletClick = onWalletClickCallback,
                                onViewAddresses = onViewAddressesCallback,
                                onScanPSBT = onScanPSBTCallback,
                                onCheckAddress = onCheckAddressCallback,
                                onExport = onExportCallback,
                                onBIP85 = onBIP85Callback,
                                onSignMessage = onSignMessageCallback
                            )
                        }
                        1 -> SettingsContent(
                            onAppearanceSettings = onAppearanceSettings,
                            onSecuritySettings = onSecuritySettings,
                            onAdvancedSettings = onAdvancedSettings,
                            onCompleteMnemonic = onCompleteMnemonic,
                            onAbout = onAbout
                        )
                    }
                }
            }
            
            // Floating bottom area: FAB + Navigation bar stacked vertically
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // FAB above the nav bar - centered over lock button (hidden in edit mode)
                val maxWallets = if (wallet.isDecoyMode) Wallet.MAX_DECOY_WALLETS else Wallet.MAX_MAIN_WALLETS
                val canShowFab = wallets.size < maxWallets && pagerState.currentPage == 0
                
                // Determine what to show: FAB, Info message, or nothing
                // Using a sealed approach with AnimatedContent for smooth crossfade
                val fabState = when {
                    isEditMode && pagerState.currentPage == 0 -> "edit_info"
                    canShowFab && !isEditMode -> "fab"
                    else -> "none"
                }
                
                androidx.compose.animation.AnimatedContent(
                    targetState = fabState,
                    transitionSpec = {
                        androidx.compose.animation.fadeIn(
                            animationSpec = androidx.compose.animation.core.tween(200)
                        ) togetherWith androidx.compose.animation.fadeOut(
                            animationSpec = androidx.compose.animation.core.tween(200)
                        ) using androidx.compose.animation.SizeTransform(clip = false)
                    },
                    label = "fab_edit_crossfade"
                ) { state ->
                    when (state) {
                        "edit_info" -> {
                            Surface(
                                modifier = Modifier
                                    .padding(bottom = 12.dp)
                                    .padding(horizontal = 8.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                tonalElevation = 2.dp
                            ) {
                                Text(
                                    text = "Drag the handle to rearrange or tap on wallet card to rename",
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                        "fab" -> {
                            FloatingActionButton(
                                onClick = { showCreateDialog = true },
                                shape = androidx.compose.foundation.shape.CircleShape,
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier
                                    .padding(bottom = 12.dp)
                                    .size(56.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_add),
                                    contentDescription = "Add Wallet",
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        else -> {
                            // Empty spacer to maintain consistent layout
                            Spacer(modifier = Modifier.height(68.dp))
                        }
                    }
                }
                
                // Floating pill-shaped bottom navigation bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(percent = 50))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Wallets Tab
                    NavigationBarItem(
                        selected = selectedTab == BottomNavTab.WALLETS,
                        onClick = {
                            if (pagerState.currentPage != 0) {
                                selectedTab = BottomNavTab.WALLETS
                                scope.launch { pagerState.animateScrollToPage(0) }
                            }
                        },
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_wallet),
                                contentDescription = "Wallets"
                            )
                        },
                        label = { Text("Wallets") },
                        modifier = Modifier.weight(1f)
                    )
                    
                    // Left divider
                    VerticalDivider(
                        modifier = Modifier.height(32.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    
                    // Lock button in center
                    FilledTonalIconButton(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            wallet.emergencyWipe()
                            navController.navigate(Screen.Unlock.route) {
                                popUpTo(0) { inclusive = true }
                            }
                        },
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .size(56.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_lock),
                            contentDescription = "Lock",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    
                    // Right divider
                    VerticalDivider(
                        modifier = Modifier.height(32.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    
                    // Settings Tab
                    NavigationBarItem(
                        selected = selectedTab == BottomNavTab.SETTINGS,
                        onClick = {
                            if (pagerState.currentPage != 1) {
                                selectedTab = BottomNavTab.SETTINGS
                                scope.launch { pagerState.animateScrollToPage(1) }
                            }
                        },
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_settings),
                                contentDescription = "Settings"
                            )
                        },
                        label = { Text("Settings") },
                        modifier = Modifier.weight(1f)
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