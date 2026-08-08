package com.gorunjinian.metrovault.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gorunjinian.metrovault.core.logging.AppLog
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
import com.gorunjinian.metrovault.feature.wallet.create.ImportMultisigScreen
import com.gorunjinian.metrovault.feature.wallet.details.AccountKeysScreen
import com.gorunjinian.metrovault.feature.wallet.details.AddressDetailScreen
import com.gorunjinian.metrovault.feature.wallet.details.AddressesScreen
import com.gorunjinian.metrovault.feature.wallet.details.BIP85DeriveScreen
import com.gorunjinian.metrovault.feature.wallet.details.DescriptorsScreen
import com.gorunjinian.metrovault.feature.wallet.details.ExportMultiSigScreen
import com.gorunjinian.metrovault.feature.wallet.details.VerifyMultisigScreen
import com.gorunjinian.metrovault.feature.wallet.details.ExportOptionsScreen
import com.gorunjinian.metrovault.feature.wallet.details.CoordinatorExportScreen
import com.gorunjinian.metrovault.feature.wallet.details.SPAddressScreen
import com.gorunjinian.metrovault.feature.wallet.details.ScriptTypeScreen
import com.gorunjinian.metrovault.feature.wallet.details.SilentPaymentExportScreen
import com.gorunjinian.metrovault.feature.wallet.details.SeedPhraseScreen
import com.gorunjinian.metrovault.feature.wallet.details.RootKeyScreen
import com.gorunjinian.metrovault.feature.wallet.details.SeedQRScreen
import com.gorunjinian.metrovault.feature.wallet.details.SignMessageScreen
import com.gorunjinian.metrovault.feature.wallet.details.WalletDetailsScreen
import com.gorunjinian.metrovault.feature.wallet.details.DifferentAccountsScreen
import com.gorunjinian.metrovault.feature.wallet.create.ImportStatelessScreen
import com.gorunjinian.metrovault.feature.settings.AppearanceSettingsScreen
import com.gorunjinian.metrovault.feature.settings.SecuritySettingsScreen
import com.gorunjinian.metrovault.feature.settings.AdvancedSettingsScreen
import com.gorunjinian.metrovault.feature.settings.WalletKeysScreen
import com.gorunjinian.metrovault.feature.settings.LibUsedScreen
import com.gorunjinian.metrovault.feature.settings.WhitePaperScreen
import java.net.URLDecoder
import java.net.URLEncoder

// Optimized animation parameters for smoother performance
private const val ANIMATION_DURATION = 250
private const val INITIAL_OFFSET_X = 0.08f // 8% horizontal offset for subtler movement

sealed class Screen(val route: String) {
    object SetupPassword : Screen("setup_password")
    object Unlock : Screen("unlock")
    object Home : Screen("home")
    object CreateWallet : Screen("create_wallet")
    object ImportWallet : Screen("import_wallet")
    object ImportMultisig : Screen("import_multisig")
    object WalletDetails : Screen("wallet_details")
    object Addresses : Screen("addresses?startTab={startTab}") {
        fun createRoute(startTab: Int = 0): String {
            return "addresses?startTab=$startTab"
        }
    }
    object AddressDetail : Screen("address_detail?address={address}&index={index}&isChange={isChange}") {
        fun createRoute(address: String, index: Int, isChange: Boolean): String {
            return "address_detail?address=${encodeNavArg(address)}&index=$index&isChange=$isChange"
        }
    }
    object ScanPSBT : Screen("scan_psbt")
    object ExportOptions : Screen("export_options")
    object CoordinatorExport : Screen("coordinator_export")
    object ExportMultiSig : Screen("export_multisig")
    object VerifyMultisig : Screen("verify_multisig?walletId={walletId}") {
        fun createRoute(walletId: String): String =
            "verify_multisig?walletId=${encodeNavArg(walletId)}"
    }
    object SilentPaymentExport : Screen("silent_payment_export")
    object SPAddress : Screen("sp_address")
    object BIP85Derive : Screen("bip85_derive")
    object SignMessage : Screen("sign_message?address={address}") {
        fun createRoute(address: String? = null): String {
            return if (address != null) {
                "sign_message?address=${encodeNavArg(address)}"
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
    object DifferentAccounts : Screen("different_accounts")
    object ScriptType : Screen("script_type")
    object AccountKeys : Screen("account_keys")
    object Descriptors : Screen("descriptors")
    object SeedPhrase : Screen("seed_phrase")
    object SeedQR : Screen("seed_qr")
    object RootKey : Screen("root_key")
    object WalletKeys : Screen("wallet_keys")
    object LibUsed : Screen("lib_used")
    object ImportStateless : Screen("import_stateless")
    object WhitePaper : Screen("white_paper")
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
    val sessionViewModel: AppSessionViewModel = viewModel()

    val scope = rememberCoroutineScope()

    // Determine start destination based on password state only - computed ONCE at startup
    // Using rememberSaveable to ensure it survives recomposition and configuration changes
    // All subsequent navigation is handled imperatively
    val startDestination = rememberSaveable {
        when {
            !secureStorage.hasMainPassword() -> Screen.SetupPassword.route
            else -> Screen.Unlock.route
        }
    }

    // Auto-open and session-expiry decisions live in AppSessionViewModel; this
    // collector is the single place its events turn into NavController calls
    LaunchedEffect(Unit) {
        sessionViewModel.navigationEvents.collect { event ->
            when (event) {
                AppSessionViewModel.NavigationEvent.ToHome -> {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Unlock.route) { inclusive = true }
                    }
                }
                AppSessionViewModel.NavigationEvent.ToHomeThenWalletDetails -> {
                    // Navigate to wallet details with Home as back destination
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Unlock.route) { inclusive = true }
                    }
                    // Small yield to let the first navigation settle before the second
                    // This prevents potential back stack inconsistency from rapid sequential navigations
                    yield()
                    navController.navigate(Screen.WalletDetails.route) {
                        launchSingleTop = true // Prevent duplicate entries
                    }
                }
                AppSessionViewModel.NavigationEvent.ToUnlock -> {
                    navController.navigate(Screen.Unlock.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            }
        }
    }

    // Track destination changes - feeds the session-expiry watcher (and debug logging)
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(currentBackStackEntry) {
        val route = currentBackStackEntry?.destination?.route
        AppLog.d("AppNavigation") { "DESTINATION CHANGED: $route" }
        sessionViewModel.onDestinationChanged(route)
    }

    // Track lifecycle state so the session-expiry watcher doesn't navigate while backgrounded
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            sessionViewModel.onAppResumedChanged(event == Lifecycle.Event.ON_RESUME)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
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
                userPreferencesRepository = userPreferencesRepository,
                onUnlockSuccess = sessionViewModel::onUnlockSuccess,
                onDataWiped = {
                    // Data was wiped due to failed login attempts - navigate to setup
                    navController.navigate(Screen.SetupPassword.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Home.route) {
            // Wipe wallet keys from memory when returning to Home (security)
            LaunchedEffect(Unit) {
                sessionViewModel.onHomeOpened()
            }
            
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
                onAbout = { navController.navigate(Screen.About.route) },
                onWhitePaper = { navController.navigate(Screen.WhitePaper.route) }
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

        composable(Screen.ImportMultisig.route) {
            ImportMultisigScreen(
                onBack = { navController.navigateUp() },
                onWalletImported = { walletId ->
                    // Wallet is created unverified — go straight into the verify/register ceremony.
                    scope.launch { wallet.refreshWallets() }
                    navController.navigate(Screen.VerifyMultisig.createRoute(walletId)) {
                        // Clear the import screens; keep Home so cancel/done returns there.
                        popUpTo(Screen.Home.route)
                    }
                }
            )
        }

        composable(
            route = Screen.VerifyMultisig.route,
            arguments = listOf(
                navArgument("walletId") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            val walletId = backStackEntry.decodedStringArg("walletId") ?: ""
            VerifyMultisigScreen(
                walletId = walletId,
                onDone = {
                    scope.launch { wallet.refreshWallets() }
                    navController.navigateUp()
                },
                onCancel = { navController.navigateUp() }
            )
        }

        composable(Screen.WalletDetails.route) {
            WalletDetailsScreen(
                wallet = wallet,
                secureStorage = secureStorage,
                userPreferencesRepository = userPreferencesRepository,
                onViewAddresses = {
                    val target = if (wallet.isActiveSilentPayment()) {
                        Screen.SPAddress.route
                    } else {
                        Screen.Addresses.createRoute()
                    }
                    navController.navigate(target)
                },
                onScanPSBT = { navController.navigate(Screen.ScanPSBT.route) },
                onExport = { navController.navigate(Screen.ExportOptions.route) },
                onExportMultiSig = { navController.navigate(Screen.ExportMultiSig.route) },
                onVerifyMultisig = { walletId -> navController.navigate(Screen.VerifyMultisig.createRoute(walletId)) },
                onBIP85 = { navController.navigate(Screen.BIP85Derive.route) },
                onSignMessage = { navController.navigate(Screen.SignMessage.createRoute()) },
                onCheckAddress = { navController.navigate(Screen.CheckAddress.route) },
                onDifferentAccounts = { navController.navigate(Screen.DifferentAccounts.route) },
                onChangeScriptType = { navController.navigate(Screen.ScriptType.route) },
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

        composable(
            route = Screen.Addresses.route,
            arguments = listOf(
                navArgument("startTab") { 
                    type = NavType.IntType 
                    defaultValue = 0
                }
            )
        ) { backStackEntry ->
            val startTab = backStackEntry.arguments?.getInt("startTab") ?: 0
            // Observe returnTab as state so it properly reacts when coming back from AddressDetail
            val returnTab by backStackEntry.savedStateHandle.getStateFlow("returnTab", startTab).collectAsState()
            
            AddressesScreen(
                wallet = wallet,
                userPreferencesRepository = userPreferencesRepository,
                initialTabIndex = returnTab,
                onBack = { navController.navigateBackOr(Screen.Home) },
                onAddressSelected = { address, index, isChange ->
                    // Set the return tab BEFORE navigating so predictive back animation works
                    backStackEntry.savedStateHandle["returnTab"] = if (isChange) 1 else 0
                    navController.navigate(Screen.AddressDetail.createRoute(address, index, isChange))
                },
                onViewSilentPaymentExport = { navController.navigate(Screen.SilentPaymentExport.route) }
            )
        }

        composable(Screen.SPAddress.route) {
            SPAddressScreen(
                wallet = wallet,
                userPreferencesRepository = userPreferencesRepository,
                onBack = { navController.navigateBackOr(Screen.WalletDetails) },
                onExport = { navController.navigate(Screen.SilentPaymentExport.route) }
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
            val address = backStackEntry.decodedStringArg("address") ?: ""
            val index = backStackEntry.arguments?.getInt("index") ?: 0
            val isChange = backStackEntry.arguments?.getBoolean("isChange") ?: false
            
            AddressDetailScreen(
                wallet = wallet,
                userPreferencesRepository = userPreferencesRepository,
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
                onBack = { navController.navigateBackOr(Screen.Home) }
            )
        }

        composable(Screen.ExportOptions.route) {
            ExportOptionsScreen(
                wallet = wallet,
                secureStorage = secureStorage,
                userPreferencesRepository = userPreferencesRepository,
                isStatelessWallet = wallet.hasStatelessWallet(),
                onBack = { navController.navigateBackOr(Screen.Home) },
                onExportCoordinator = { navController.navigate(Screen.CoordinatorExport.route) },
                onViewAccountKeys = { navController.navigate(Screen.AccountKeys.route) },
                onViewDescriptors = { navController.navigate(Screen.Descriptors.route) },
                onViewRootKey = { navController.navigate(Screen.RootKey.route) },
                onViewSeedPhrase = { navController.navigate(Screen.SeedPhrase.route) },
                onViewSilentPayments = { navController.navigate(Screen.SilentPaymentExport.route) }
            )
        }

        composable(Screen.CoordinatorExport.route) {
            CoordinatorExportScreen(
                wallet = wallet,
                onBack = { navController.navigateBackOr(Screen.ExportOptions) }
            )
        }

        composable(Screen.SilentPaymentExport.route) {
            SilentPaymentExportScreen(
                wallet = wallet,
                userPreferencesRepository = userPreferencesRepository,
                onBack = { navController.navigateBackOr(Screen.ExportOptions) }
            )
        }

        composable(Screen.ExportMultiSig.route) {
            // Metadata load + address derivation are disk/crypto work - run once, off Main
            val exportData by produceState<Pair<String, String>?>(initialValue = null) {
                value = withContext(Dispatchers.IO) {
                    val metadata = wallet.getActiveWalletId()?.let { id ->
                        secureStorage.loadWalletMetadata(id, wallet.isDecoyMode)
                    }
                    val descriptor = metadata?.multisigConfig?.rawDescriptor ?: ""
                    // First receive address for BSMS format
                    val firstAddress = wallet.generateAddresses(1, 0, isChange = false)
                        ?.firstOrNull()?.address ?: ""
                    descriptor to firstAddress
                }
            }

            val data = exportData
            if (data == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                ExportMultiSigScreen(
                    descriptor = data.first,
                    firstAddress = data.second,
                    onBack = { navController.navigateBackOr(Screen.Home) }
                )
            }
        }

        composable(Screen.AccountKeys.route) {
            AccountKeysScreen(
                wallet = wallet,
                secureStorage = secureStorage,
                userPreferencesRepository = userPreferencesRepository,
                onBack = { navController.navigateBackOr(Screen.ExportOptions) }
            )
        }

        composable(Screen.Descriptors.route) {
            DescriptorsScreen(
                wallet = wallet,
                secureStorage = secureStorage,
                userPreferencesRepository = userPreferencesRepository,
                onBack = { navController.navigateBackOr(Screen.ExportOptions) }
            )
        }

        composable(Screen.SeedPhrase.route) {
            SeedPhraseScreen(
                wallet = wallet,
                onBack = { navController.navigateBackOr(Screen.ExportOptions) },
                onShowSeedQR = {
                    navController.navigate(Screen.SeedQR.route)
                }
            )
        }

        composable(Screen.SeedQR.route) {
            val mnemonic = wallet.getActiveMnemonic() ?: emptyList()
            SeedQRScreen(
                mnemonic = mnemonic,
                onBack = {
                    // Normal back navigation to SeedPhraseScreen (preserves predictive animation)
                    navController.popBackStack()
                },
                onBackToExportOptions = {
                    // Button: skip SeedPhraseScreen and go directly to ExportOptions
                    navController.popBackStack(Screen.ExportOptions.route, inclusive = false)
                }
            )
        }

        composable(Screen.RootKey.route) {
            RootKeyScreen(
                wallet = wallet,
                userPreferencesRepository = userPreferencesRepository,
                onBack = { navController.navigateBackOr(Screen.ExportOptions) },
                onBackToExportOptions = {
                    navController.popBackStack(Screen.ExportOptions.route, inclusive = false)
                }
            )
        }

        composable(Screen.BIP85Derive.route) {
            BIP85DeriveScreen(
                wallet = wallet,
                onBack = { navController.navigateBackOr(Screen.Home) }
            )
        }

        composable(Screen.DifferentAccounts.route) {
            DifferentAccountsScreen(
                wallet = wallet,
                secureStorage = secureStorage,
                onBack = { navController.navigateBackOr(Screen.Home) }
            )
        }

        composable(Screen.ScriptType.route) {
            ScriptTypeScreen(
                wallet = wallet,
                onBack = { navController.navigateBackOr(Screen.Home) }
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
            val address = backStackEntry.decodedStringArg("address")
            SignMessageScreen(
                onBack = { navController.navigateBackOr(Screen.Home) },
                prefilledAddress = address
            )
        }

        composable(Screen.CheckAddress.route) {
            CheckAddressScreen(
                wallet = wallet,
                onBack = { navController.navigateBackOr(Screen.Home) }
            )
        }

        composable(Screen.CompleteMnemonic.route) {
            CompleteMnemonicScreen(
                onBack = { navController.navigateBackOr(Screen.Home) }
            )
        }

        composable(Screen.About.route) {
            AboutScreen(
                onBack = { navController.navigateBackOr(Screen.Home) },
                onLibUsed = {
                    navController.navigate(Screen.LibUsed.route)
                }
            )
        }

        composable(Screen.LibUsed.route) {
            LibUsedScreen(
                onBack = { navController.navigateBackOr(Screen.About) }
            )
        }

        composable(Screen.SettingsAppearance.route) {
            AppearanceSettingsScreen(
                userPreferencesRepository = userPreferencesRepository,
                onBack = { navController.navigateBackOr(Screen.Home) }
            )
        }

        composable(Screen.SettingsSecurity.route) {
            SecuritySettingsScreen(
                wallet = wallet,
                secureStorage = secureStorage,
                userPreferencesRepository = userPreferencesRepository,
                activity = activity,
                onBack = { navController.navigateBackOr(Screen.Home) }
            )
        }

        composable(Screen.SettingsAdvanced.route) {
            AdvancedSettingsScreen(
                wallet = wallet,
                secureStorage = secureStorage,
                userPreferencesRepository = userPreferencesRepository,
                onBack = { navController.navigateBackOr(Screen.Home) },
                onViewSavedKeys = {
                    navController.navigate(Screen.WalletKeys.route)
                }
            )
        }

        composable(Screen.WalletKeys.route) {
            WalletKeysScreen(
                wallet = wallet,
                secureStorage = secureStorage,
                onBack = { navController.navigateBackOr(Screen.SettingsAdvanced) }
            )
        }

        composable(Screen.ImportStateless.route) {
            ImportStatelessScreen(
                onBack = { navController.navigateUp() },
                onWalletCreated = {
                    // Navigate to unified WalletDetailsScreen for stateless wallets
                    navController.navigate(Screen.WalletDetails.route) {
                        popUpTo(Screen.Home.route) { inclusive = false }
                    }
                }
            )
        }

        composable(Screen.WhitePaper.route) {
            WhitePaperScreen(
                onBack = { navController.navigateBackOr(Screen.Home) }
            )
        }
    }
}

/** Pop the back stack, or navigate to [fallback] when there is nothing to pop. */
private fun NavHostController.navigateBackOr(fallback: Screen) {
    if (!popBackStack()) {
        navigate(fallback.route)
    }
}

private fun encodeNavArg(value: String): String = URLEncoder.encode(value, "UTF-8")

private fun NavBackStackEntry.decodedStringArg(key: String): String? =
    arguments?.getString(key)?.let { URLDecoder.decode(it, "UTF-8") }
