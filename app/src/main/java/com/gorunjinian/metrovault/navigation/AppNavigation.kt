package com.gorunjinian.metrovault.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavType
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gorunjinian.metrovault.core.crypto.SessionKeyManager
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
import com.gorunjinian.metrovault.feature.wallet.details.AddressDetailScreen
import com.gorunjinian.metrovault.feature.wallet.details.AddressesScreen
import com.gorunjinian.metrovault.feature.wallet.details.BIP85DeriveScreen
import com.gorunjinian.metrovault.feature.wallet.details.ExportOptionsScreen
import com.gorunjinian.metrovault.feature.wallet.details.SignMessageScreen
import com.gorunjinian.metrovault.feature.wallet.details.WalletDetailsScreen
import com.gorunjinian.metrovault.feature.settings.AppearanceSettingsScreen
import com.gorunjinian.metrovault.feature.settings.SecuritySettingsScreen
import com.gorunjinian.metrovault.feature.settings.AdvancedSettingsScreen

// Optimized animation parameters for smoother performance
private const val ANIMATION_DURATION = 250
private const val INITIAL_OFFSET_X = 0.08f // 8% horizontal offset for subtler movement

sealed class Screen(val route: String) {
    object SetupPassword : Screen("setup_password")
    object Unlock : Screen("unlock")
    object Home : Screen("home")
    object CreateWallet : Screen("create_wallet")
    object ImportWallet : Screen("import_wallet")
    object WalletDetails : Screen("wallet_details")
    object Addresses : Screen("addresses")
    object AddressDetail : Screen("address_detail?address={address}&index={index}&isChange={isChange}") {
        fun createRoute(address: String, index: Int, isChange: Boolean): String {
            return "address_detail?address=${java.net.URLEncoder.encode(address, "UTF-8")}&index=$index&isChange=$isChange"
        }
    }
    object ScanPSBT : Screen("scan_psbt")
    object ExportOptions : Screen("export_options")
    object BIP85Derive : Screen("bip85_derive")
    object SignMessage : Screen("sign_message?address={address}") {
        fun createRoute(address: String? = null): String {
            return if (address != null) {
                "sign_message?address=${java.net.URLEncoder.encode(address, "UTF-8")}"
            } else {
                "sign_message"
            }
        }
    }
    object CheckAddress : Screen("check_address")
    object CompleteMnemonic : Screen("complete_mnemonic")
    object About : Screen("about")
    object SettingsAppearance : Screen("settings_appearance")
    object SettingsSecurity : Screen("settings_security")
    object SettingsAdvanced : Screen("settings_advanced")
}

@Suppress("AssignedValueIsNeverRead")
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

    // Track lifecycle state to avoid navigating when app is backgrounded
    val lifecycleOwner = LocalLifecycleOwner.current
    var isAppResumed by remember { mutableStateOf(true) }
    
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            isAppResumed = event == Lifecycle.Event.ON_RESUME
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Observe session state - navigate to unlock screen when session expires
    // Only navigate if app is in foreground to avoid flashing unlock screen when backgrounding
    val sessionKeyManager = remember { SessionKeyManager.getInstance() }
    val isSessionActive by sessionKeyManager.isSessionActive.collectAsState()

    LaunchedEffect(isSessionActive, isAppResumed) {
        val currentRoute = currentBackStackEntry?.destination?.route
        // If session becomes inactive, app is resumed, and we're not already on auth screens, navigate to unlock
        if (!isSessionActive &&
            isAppResumed &&
            currentRoute != null &&
            currentRoute != Screen.Unlock.route &&
            currentRoute != Screen.SetupPassword.route) {
            android.util.Log.d("AppNavigation", "Session expired while app resumed - navigating to unlock screen")
            navController.navigate(Screen.Unlock.route) {
                popUpTo(0) { inclusive = true }
            }
        }
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
                },
                onDataWiped = {
                    // Data was wiped due to failed login attempts - navigate to setup
                    navController.navigate(Screen.SetupPassword.route) {
                        popUpTo(0) { inclusive = true }
                    }
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
                onCompleteMnemonic = { navController.navigate(Screen.CompleteMnemonic.route) },
                onAppearanceSettings = { navController.navigate(Screen.SettingsAppearance.route) },
                onSecuritySettings = { navController.navigate(Screen.SettingsSecurity.route) },
                onAdvancedSettings = { navController.navigate(Screen.SettingsAdvanced.route) },
                onAbout = { navController.navigate(Screen.About.route) }
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
                onSignMessage = { navController.navigate(Screen.SignMessage.createRoute()) },
                onCheckAddress = { navController.navigate(Screen.CheckAddress.route) },
                onLock = {
                    navController.navigate(Screen.Unlock.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
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
                },
                onAddressSelected = { address, index, isChange ->
                    navController.navigate(Screen.AddressDetail.createRoute(address, index, isChange))
                }
            )
        }

        composable(
            route = Screen.AddressDetail.route,
            arguments = listOf(
                navArgument("address") { type = NavType.StringType },
                navArgument("index") { type = NavType.IntType },
                navArgument("isChange") { type = NavType.BoolType }
            )
        ) { backStackEntry ->
            val address = java.net.URLDecoder.decode(
                backStackEntry.arguments?.getString("address") ?: "",
                "UTF-8"
            )
            val index = backStackEntry.arguments?.getInt("index") ?: 0
            val isChange = backStackEntry.arguments?.getBoolean("isChange") ?: false
            
            AddressDetailScreen(
                wallet = wallet,
                address = address,
                addressIndex = index,
                isChange = isChange,
                onBack = { navController.popBackStack() },
                onSignMessage = { addr ->
                    navController.navigate(Screen.SignMessage.createRoute(addr))
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

        composable(
            route = Screen.SignMessage.route,
            arguments = listOf(
                navArgument("address") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val address = backStackEntry.arguments?.getString("address")?.let {
                java.net.URLDecoder.decode(it, "UTF-8")
            }
            SignMessageScreen(
                wallet = wallet,
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Screen.Home.route)
                    }
                },
                prefilledAddress = address
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

        composable(Screen.SettingsAppearance.route) {
            AppearanceSettingsScreen(
                userPreferencesRepository = userPreferencesRepository,
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Screen.Home.route)
                    }
                }
            )
        }

        composable(Screen.SettingsSecurity.route) {
            SecuritySettingsScreen(
                wallet = wallet,
                secureStorage = secureStorage,
                userPreferencesRepository = userPreferencesRepository,
                activity = activity,
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Screen.Home.route)
                    }
                }
            )
        }

        composable(Screen.SettingsAdvanced.route) {
            AdvancedSettingsScreen(
                wallet = wallet,
                secureStorage = secureStorage,
                userPreferencesRepository = userPreferencesRepository,
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Screen.Home.route)
                    }
                }
            )
        }
    }
}
