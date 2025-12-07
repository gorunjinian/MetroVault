package com.gorunjinian.metrovault.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.data.repository.UserPreferencesRepository
import com.gorunjinian.metrovault.feature.auth.SetupPasswordScreen
import com.gorunjinian.metrovault.feature.auth.UnlockScreen
import com.gorunjinian.metrovault.feature.home.HomeScreen
import com.gorunjinian.metrovault.feature.settings.AboutScreen
import com.gorunjinian.metrovault.feature.transaction.CheckAddressScreen
import com.gorunjinian.metrovault.feature.transaction.ScanPSBTScreen
import com.gorunjinian.metrovault.feature.wallet.create.CompleteMnemonicScreen
import com.gorunjinian.metrovault.feature.wallet.create.CreateWalletScreen
import com.gorunjinian.metrovault.feature.wallet.create.ImportWalletScreen
import com.gorunjinian.metrovault.feature.wallet.details.AddressesScreen
import com.gorunjinian.metrovault.feature.wallet.details.BIP85DeriveScreen
import com.gorunjinian.metrovault.feature.wallet.details.ExportOptionsScreen
import com.gorunjinian.metrovault.feature.wallet.details.WalletDetailsScreen

// Optimized animation parameters for smoother performance
private const val ANIMATION_DURATION = 250
private const val INITIAL_SCALE = 0.95f
private const val INITIAL_OFFSET_X = 0.08f // 8% horizontal offset for subtler movement

sealed class Screen(val route: String) {
    object SetupPassword : Screen("setup_password")
    object Unlock : Screen("unlock")
    object Home : Screen("home")
    object CreateWallet : Screen("create_wallet")
    object ImportWallet : Screen("import_wallet")
    object WalletDetails : Screen("wallet_details")
    object Addresses : Screen("addresses")
    object ScanPSBT : Screen("scan_psbt")
    object ExportOptions : Screen("export_options")
    object BIP85Derive : Screen("bip85_derive")
    object CheckAddress : Screen("check_address")
    object CompleteMnemonic : Screen("complete_mnemonic")
    object About : Screen("about")
}

@Composable
fun AppNavigation(
    userPreferencesRepository: UserPreferencesRepository = UserPreferencesRepository(LocalContext.current)
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val activity = context as androidx.fragment.app.FragmentActivity
     val secureStorage = remember { SecureStorage(context) }
    val wallet = remember { Wallet.getInstance(context) }

    val scope = rememberCoroutineScope()

    // Auto-open state - placed at AppNavigation level so it survives NavHost recomposition
    // null = no pending operation, true = auto-open requested, false = normal unlock
    var pendingAutoOpen by remember { mutableStateOf<Boolean?>(null) }

    // Guard against re-entry during wallet loading - prevents race conditions
    var isProcessingAutoOpen by remember { mutableStateOf(false) }

    // Determine start destination based on password state only - computed ONCE at startup
    // Using rememberSaveable to ensure it survives recomposition and configuration changes
    // All subsequent navigation is handled imperatively
    val startDestination = rememberSaveable {
        when {
            !secureStorage.hasMainPassword() -> Screen.SetupPassword.route
            else -> Screen.Unlock.route
        }
    }
    
    // Track wallets for other purposes (not for startDestination)
    val walletsList by wallet.wallets.collectAsState()
    
    // Handle wallet loading and navigation when auto-open is triggered
    // This LaunchedEffect is at the AppNavigation level, so it won't be cancelled
    // when the Unlock screen is disposed due to walletsList state changes
    LaunchedEffect(pendingAutoOpen) {
        val autoOpen = pendingAutoOpen ?: return@LaunchedEffect

        // Guard against re-entry - prevents race condition where effect triggers multiple times
        if (isProcessingAutoOpen) {
            android.util.Log.d("AppNavigation", "Already processing auto-open, skipping")
            return@LaunchedEffect
        }
        isProcessingAutoOpen = true

        android.util.Log.d("AppNavigation", "LaunchedEffect triggered: autoOpen=$autoOpen")

        try {
            if (autoOpen) {
                // Auto-open: Load the wallet before navigating
                val wallets = wallet.wallets.value
                android.util.Log.d("AppNavigation", "Checking wallets: size=${wallets.size}")
                if (wallets.size == 1) {
                    val singleWallet = wallets.first()
                    android.util.Log.d("AppNavigation", "Loading wallet: ${singleWallet.id}")
                    val loaded = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        wallet.openWallet(singleWallet.id, showLoading = false)
                    }
                    android.util.Log.d("AppNavigation", "Wallet loaded: $loaded")

                    if (loaded) {
                        // Clear pending state
                        pendingAutoOpen = null

                        // Navigate to wallet details with Home as back destination
                        // First navigate to Home, clearing the unlock screen
                        android.util.Log.d("AppNavigation", "Navigating to Home then WalletDetails")
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Unlock.route) { inclusive = true }
                        }
                        // Small yield to let the first navigation settle before the second
                        // This prevents potential back stack inconsistency from rapid sequential navigations
                        kotlinx.coroutines.yield()
                        // Then navigate to WalletDetails on top of Home
                        navController.navigate(Screen.WalletDetails.route) {
                            launchSingleTop = true // Prevent duplicate entries
                        }
                        android.util.Log.d("AppNavigation", "Navigation complete")
                        return@LaunchedEffect
                    }
                }
            }

            // Default: navigate to home
            android.util.Log.d("AppNavigation", "Default: navigating to Home")
            pendingAutoOpen = null
            navController.navigate(Screen.Home.route) {
                popUpTo(Screen.Unlock.route) { inclusive = true }
            }
        } finally {
            isProcessingAutoOpen = false
        }
    }
    
    // Debug: Track all destination changes
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(currentBackStackEntry) {
        android.util.Log.d("AppNavigation", "DESTINATION CHANGED: ${currentBackStackEntry?.destination?.route}")
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        // Forward navigation: simplified for better performance
        enterTransition = {
            fadeIn(animationSpec = tween(ANIMATION_DURATION, easing = FastOutSlowInEasing)) +
            slideInHorizontally(
                initialOffsetX = { (it * INITIAL_OFFSET_X).toInt() },
                animationSpec = tween(ANIMATION_DURATION, easing = FastOutSlowInEasing)
            )
        },
        // Forward navigation: simplified exit
        exitTransition = {
            fadeOut(animationSpec = tween(ANIMATION_DURATION, easing = FastOutSlowInEasing)) +
            slideOutHorizontally(
                targetOffsetX = { -(it * INITIAL_OFFSET_X).toInt() },
                animationSpec = tween(ANIMATION_DURATION, easing = FastOutSlowInEasing)
            )
        },
        // Back navigation: simplified enter
        popEnterTransition = {
            fadeIn(animationSpec = tween(ANIMATION_DURATION, easing = FastOutSlowInEasing)) +
            slideInHorizontally(
                initialOffsetX = { -(it * INITIAL_OFFSET_X).toInt() },
                animationSpec = tween(ANIMATION_DURATION, easing = FastOutSlowInEasing)
            )
        },
        // Back navigation: simplified exit
        popExitTransition = {
            fadeOut(animationSpec = tween(ANIMATION_DURATION, easing = FastOutSlowInEasing)) +
            slideOutHorizontally(
                targetOffsetX = { (it * INITIAL_OFFSET_X).toInt() },
                animationSpec = tween(ANIMATION_DURATION, easing = FastOutSlowInEasing)
            )
        }
    ) {
        composable(Screen.SetupPassword.route) {
            SetupPasswordScreen(
                onPasswordSet = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.SetupPassword.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Unlock.route) {
            UnlockScreen(
                onUnlockSuccess = { autoOpenRequested ->
                    // Set state at AppNavigation level to trigger the LaunchedEffect
                    pendingAutoOpen = autoOpenRequested
                }
            )
        }

        composable(Screen.Home.route) {
            HomeScreen(
                navController = navController,
                wallet = wallet,
                secureStorage = secureStorage,
                userPreferencesRepository = userPreferencesRepository,
                activity = activity,
                onCompleteMnemonic = { navController.navigate(Screen.CompleteMnemonic.route) }
            )
        }

        composable(Screen.CreateWallet.route) {
            CreateWalletScreen(
                onBack = { navController.navigateUp() },
                onWalletCreated = {
                    scope.launch {
                        wallet.refreshWallets()
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Home.route) { inclusive = true }
                        }
                    }
                }
            )
        }

        composable(Screen.ImportWallet.route) {
            ImportWalletScreen(
                wallet = wallet,
                secureStorage = secureStorage,
                onBack = { navController.navigateUp() },
                onWalletImported = {
                    scope.launch {
                        wallet.refreshWallets()
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Home.route) { inclusive = true }
                        }
                    }
                }
            )
        }

        composable(Screen.WalletDetails.route) {
            WalletDetailsScreen(
                wallet = wallet,
                secureStorage = secureStorage,
                onViewAddresses = { navController.navigate(Screen.Addresses.route) },
                onScanPSBT = { navController.navigate(Screen.ScanPSBT.route) },
                onExport = { navController.navigate(Screen.ExportOptions.route) },
                onBIP85 = { navController.navigate(Screen.BIP85Derive.route) },
                onCheckAddress = { navController.navigate(Screen.CheckAddress.route) },
                onBack = {
                    if (navController.previousBackStackEntry != null) {
                        navController.popBackStack()
                    } else {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Home.route) { inclusive = true }
                        }
                    }
                }
            )
        }

        composable(Screen.Addresses.route) {
            AddressesScreen(
                wallet = wallet,
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Screen.Home.route)
                    }
                }
            )
        }

        composable(Screen.ScanPSBT.route) {
            ScanPSBTScreen(
                wallet = wallet,
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Screen.Home.route)
                    }
                }
            )
        }

        composable(Screen.ExportOptions.route) {
            ExportOptionsScreen(
                wallet = wallet,
                secureStorage = secureStorage,
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Screen.Home.route)
                    }
                }
            )
        }

        composable(Screen.BIP85Derive.route) {
            BIP85DeriveScreen(
                wallet = wallet,
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Screen.Home.route)
                    }
                }
            )
        }

        composable(Screen.CheckAddress.route) {
            CheckAddressScreen(
                wallet = wallet,
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Screen.Home.route)
                    }
                }
            )
        }

        composable(Screen.CompleteMnemonic.route) {
            CompleteMnemonicScreen(
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Screen.Home.route)
                    }
                }
            )
        }

        composable(Screen.About.route) {
            AboutScreen(
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Screen.Home.route)
                    }
                }
            )
        }
    }
}
