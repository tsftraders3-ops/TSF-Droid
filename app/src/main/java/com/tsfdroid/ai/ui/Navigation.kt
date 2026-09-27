package com.tsfdroid.ai.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.scale
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.tsfdroid.ai.ui.screens.*
import com.tsfdroid.ai.ui.theme.*
import com.tsfdroid.ai.ui.viewmodel.*

/**
 * Route names of the top-level navigation graph.
 *
 * Kept next to [navigateAfterSplash] and [navigateAfterOnboarding] so the back-stack
 * rules of the entry flow can be exercised without composing the screens themselves.
 */
object OpenDroidRoutes {
    const val SPLASH = "splash"
    const val ONBOARDING = "onboarding"
    const val MAIN = "main"
    const val BENCHMARK = "benchmark"
    const val PRIVACY_POLICY = "privacy_policy"
    const val TERMS_OF_USE = "terms_of_use"
    const val HELP_CENTER = "help_center"
    const val LICENSE = "license"
    const val ABOUT = "about"
    const val AUTO_REPLY_SETTINGS = "auto_reply_settings"
    const val NOTIFICATION_HISTORY = "notification_history"
    const val PERMISSIONS = "permissions"
    const val CRASH_LOG = "crash_log"
    const val ROUTINES = "routines"
    const val SOCIAL = "social"
}

/**
 * Leaves the splash screen for onboarding or the main dashboard, dropping splash from the
 * back stack so system back from the first real screen exits the app instead of replaying it.
 */
fun NavHostController.navigateAfterSplash(isOnboardingCompleted: Boolean) {
    val destination = if (isOnboardingCompleted) OpenDroidRoutes.MAIN else OpenDroidRoutes.ONBOARDING
    navigate(destination) {
        popUpTo(OpenDroidRoutes.SPLASH) { inclusive = true }
    }
}

/** Enters the dashboard after onboarding, so back never returns to the completed flow. */
fun NavHostController.navigateAfterOnboarding() {
    navigate(OpenDroidRoutes.MAIN) {
        popUpTo(OpenDroidRoutes.ONBOARDING) { inclusive = true }
    }
}

@Composable
fun OpenDroidNavigation(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = OpenDroidRoutes.SPLASH,
        modifier = Modifier.fillMaxSize().background(AppTheme.colors.background),
        enterTransition = {
            fadeIn(animationSpec = tween(300)) + slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(300, easing = FastOutSlowInEasing)
            )
        },
        exitTransition = {
            fadeOut(animationSpec = tween(250)) + slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.Start,
                targetOffset = { it / 4 },
                animationSpec = tween(250, easing = FastOutSlowInEasing)
            )
        },
        popEnterTransition = {
            fadeIn(animationSpec = tween(300)) + slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                initialOffset = { it / 4 },
                animationSpec = tween(300, easing = FastOutSlowInEasing)
            )
        },
        popExitTransition = {
            fadeOut(animationSpec = tween(250)) + slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(250, easing = FastOutSlowInEasing)
            )
        }
    ) {
        composable(OpenDroidRoutes.SPLASH) {
            val startupViewModel: StartupViewModel = hiltViewModel()
            val startDestination by startupViewModel.startDestination.collectAsState()
            var splashFinished by remember { mutableStateOf(false) }

            SplashScreen(onNavigateNext = { splashFinished = true })

            // The destination is decided off the main thread, so wait for both the animation and
            // the decrypted profile check rather than guessing a route.
            LaunchedEffect(splashFinished, startDestination) {
                val destination = startDestination
                if (splashFinished && destination != null) {
                    navController.navigateAfterSplash(
                        isOnboardingCompleted = destination == StartDestination.MAIN
                    )
                }
            }
        }

        composable(OpenDroidRoutes.ONBOARDING) {
            OnboardingScreen(
                onFinished = { navController.navigateAfterOnboarding() }
            )
        }

        composable(OpenDroidRoutes.MAIN) {
            MainDashboard(
                onNavigateToBenchmark = {
                    navController.navigate(OpenDroidRoutes.BENCHMARK)
                },
                onNavigateToPrivacyPolicy = {
                    navController.navigate(OpenDroidRoutes.PRIVACY_POLICY)
                },
                onNavigateToTermsOfUse = {
                    navController.navigate(OpenDroidRoutes.TERMS_OF_USE)
                },
                onNavigateToHelpCenter = {
                    navController.navigate(OpenDroidRoutes.HELP_CENTER)
                },
                onNavigateToLicense = {
                    navController.navigate(OpenDroidRoutes.LICENSE)
                },
                onNavigateToAbout = {
                    navController.navigate(OpenDroidRoutes.ABOUT)
                },
                onNavigateToAutoReply = {
                    navController.navigate(OpenDroidRoutes.AUTO_REPLY_SETTINGS)
                },
                onNavigateToNotificationHistory = {
                    navController.navigate(OpenDroidRoutes.NOTIFICATION_HISTORY)
                },
                onNavigateToPermissions = {
                    navController.navigate(OpenDroidRoutes.PERMISSIONS)
                },
                onNavigateToCrashLog = {
                    navController.navigate(OpenDroidRoutes.CRASH_LOG)
                },
                onNavigateToRoutines = {
                    navController.navigate(OpenDroidRoutes.ROUTINES)
                }
            )
        }

        composable(OpenDroidRoutes.BENCHMARK) {
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            BenchmarkScreen(
                viewModel = settingsViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OpenDroidRoutes.PRIVACY_POLICY) {
            PrivacyPolicyScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OpenDroidRoutes.ABOUT) {
            AboutScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OpenDroidRoutes.TERMS_OF_USE) {
            TermsOfUseScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OpenDroidRoutes.HELP_CENTER) {
            HelpCenterScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OpenDroidRoutes.LICENSE) {
            LicenseScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OpenDroidRoutes.AUTO_REPLY_SETTINGS) {
            val settingsRepo = hiltViewModel<AutoReplyViewModel>().settingsRepository
            AutoReplySettingsScreen(
                settingsRepository = settingsRepo,
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OpenDroidRoutes.NOTIFICATION_HISTORY) {
            val notifDao = hiltViewModel<NotificationHistoryViewModel>().notificationDao
            NotificationHistoryScreen(
                notificationDao = notifDao,
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OpenDroidRoutes.PERMISSIONS) {
            PermissionsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OpenDroidRoutes.CRASH_LOG) {
            CrashLogScreen(
                viewModel = hiltViewModel(),
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OpenDroidRoutes.ROUTINES) {
            val routineViewModel: com.tsfdroid.ai.ui.viewmodel.RoutineViewModel = hiltViewModel()
            com.tsfdroid.ai.ui.screens.RoutinesScreen(
                viewModel = routineViewModel,
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(OpenDroidRoutes.SOCIAL) {
            val socialViewModel: SocialViewModel = hiltViewModel()
            SocialScreen(viewModel = socialViewModel)
        }
    }
}

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Chat : Screen("chat", "Chat", Icons.Default.Chat)
    object Plan : Screen("plan", "Plan", Icons.Default.List)
    object Memory : Screen("memory", "Memory", Icons.Default.Star)
    object Social : Screen("social", "Social", Icons.Default.Share)
    object Macros : Screen("macros", "Macros", Icons.Default.Build)
    object History : Screen("history", "Logs", Icons.Default.History)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

/**
 * Aurora bottom navigation (prototype `.bnav`): four primary destinations —
 * Chat / Plan / Memory / Macros — plus More, which opens the M3 bottom sheet
 * holding the remaining sections. The selected indicator is the 56×31 primary-
 * container pill; colors adapt via AppTheme tokens.
 */
private val primaryNavScreens = listOf(Screen.Chat, Screen.Plan, Screen.Memory, Screen.Macros)

@Composable
private fun AuroraBottomNav(
    currentTab: Screen,
    onSelect: (Screen) -> Unit,
    onMore: () -> Unit
) {
    val colors = AppTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surfaceLow)
    ) {
        // top hairline only, per prototype `.bnav`
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.5.dp)
                .background(colors.outlineVariant)
        )
        Row(
            Modifier
                .navigationBarsPadding()
                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            primaryNavScreens.forEach { screen ->
                AuroraNavItem(
                    screen = screen,
                    selected = currentTab == screen,
                    onClick = { onSelect(screen) },
                    modifier = Modifier.weight(1f)
                )
            }
            AuroraNavItem(
                screen = null,
                label = "More",
                selected = currentTab !in primaryNavScreens,
                onClick = onMore,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun AuroraNavItem(
    screen: Screen?,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = AppTheme.colors
    val icon = screen?.icon
    val textColor by animateColorAsState(
        if (selected) colors.onPrimaryContainer else colors.textSecondary,
        tween(160), label = "navText"
    )
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(top = 4.dp, bottom = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Box(
            Modifier
                .width(56.dp)
                .height(31.dp)
                .background(
                    if (selected) colors.primaryContainer else Color.Transparent,
                    RoundedCornerShape(999.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = label, tint = textColor, modifier = Modifier.size(21.dp))
            } else {
                Icon(Icons.Default.MoreHoriz, contentDescription = label, tint = textColor, modifier = Modifier.size(21.dp))
            }
        }
        Text(label, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = textColor)
    }
}

/**
 * The Aurora "More" bottom sheet (prototype `.sheet`): 28px crown, grab handle,
 * Bricolage heading, sheet items with trailing hint labels.
 */
@Composable
private fun AuroraMoreSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    onNavigate: (Screen) -> Unit,
    onNavigateToRoutines: () -> Unit,
    onNavigateToPermissions: () -> Unit,
    onNavigateToNotificationHistory: () -> Unit,
    routineCount: Int,
    pendingCount: Int
) {
    val colors = AppTheme.colors
    if (!visible) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceHigh,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        tonalElevation = 0.dp
    ) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 14.dp)
                .width(34.dp)
                .height(4.dp)
                .background(colors.outline, RoundedCornerShape(99.dp))
        )
        Text(
            "More",
            style = MaterialTheme.typography.headlineSmall,
            color = colors.textPrimary,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
        )
        MoreSheetItem(Icons.Default.AutoAwesome, "Routines", "$routineCount detected") { onNavigateToRoutines() }
        MoreSheetItem(Icons.Default.Share, "Social", "7 platforms") { onNavigate(Screen.Social) }
        MoreSheetItem(Icons.Default.Notifications, "Notifications", "$pendingCount pending") { onNavigateToNotificationHistory() }
        MoreSheetItem(Icons.Default.Lock, "Permissions", null) { onNavigateToPermissions() }
        MoreSheetItem(Icons.Default.History, "Logs", null) { onNavigate(Screen.History) }
        MoreSheetItem(Icons.Default.Settings, "Settings", null) { onNavigate(Screen.Settings) }
        Spacer(Modifier.height(26.dp))
    }
}

@Composable
private fun MoreSheetItem(
    icon: ImageVector,
    label: String,
    hint: String?,
    onClick: () -> Unit
) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp + 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(icon, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
        Text(label, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary, modifier = Modifier.weight(1f))
        if (hint != null) {
            Text(hint, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = colors.textSecondary)
        }
    }
}

@Composable
fun MainDashboard(
    onNavigateToBenchmark: () -> Unit,
    onNavigateToPrivacyPolicy: () -> Unit,
    onNavigateToTermsOfUse: () -> Unit,
    onNavigateToHelpCenter: () -> Unit,
    onNavigateToLicense: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToAutoReply: () -> Unit = {},
    onNavigateToNotificationHistory: () -> Unit = {},
    onNavigateToPermissions: () -> Unit = {},
    onNavigateToCrashLog: () -> Unit = {},
    onNavigateToRoutines: () -> Unit = {}
) {
    val context = LocalContext.current

    // Start the service as soon as RECORD_AUDIO is granted, and keep checking on every
    // resume - not just once on first composition - so a user who grants the microphone
    // from Settings > Permissions (rather than at onboarding) gets the service started
    // immediately on returning here, with no app restart required.
    var recordAudioServiceStarted by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (granted && !recordAudioServiceStarted) {
                    recordAudioServiceStarted = true
                    com.tsfdroid.ai.core.service.OpenDroidService.start(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var currentTab by remember { mutableStateOf<Screen>(Screen.Chat) }
    var moreSheetVisible by remember { mutableStateOf(false) }

    val chatViewModel: ChatViewModel = hiltViewModel()
    val planViewModel: PlanViewModel = hiltViewModel()
    val memoryViewModel: MemoryViewModel = hiltViewModel()
    val socialViewModel: SocialViewModel = hiltViewModel()
    val macroViewModel: MacroViewModel = hiltViewModel()
    val historyViewModel: HistoryViewModel = hiltViewModel()
    val settingsViewModel: SettingsViewModel = hiltViewModel()

    Scaffold(
        bottomBar = {
            AuroraBottomNav(
                currentTab = currentTab,
                onSelect = { currentTab = it },
                onMore = { moreSheetVisible = true }
            )
        },
        containerColor = AppTheme.colors.background
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
        ) {
            AnimatedContent(
                targetState = currentTab,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(AuroraMotion.DurationScreenEnter, easing = AuroraMotion.EasingEmphasized)) +
                        slideInVertically(animationSpec = tween(AuroraMotion.DurationScreenEnter, easing = AuroraMotion.EasingEmphasized)) { it / 16 })
                        .togetherWith(fadeOut(animationSpec = tween(180)))
                },
                label = "DashboardTabTransition"
            ) { tab ->
                when (tab) {
                    Screen.Chat -> ChatScreen(viewModel = chatViewModel)
                    Screen.Plan -> PlanScreen(viewModel = planViewModel)
                    Screen.Memory -> MemoryScreen(viewModel = memoryViewModel)
                    Screen.Social -> SocialScreen(viewModel = socialViewModel)
                    Screen.Macros -> MacrosScreen(
                        viewModel = macroViewModel,
                        onNavigateToRoutines = onNavigateToRoutines
                    )
                    Screen.History -> LogsScreen(viewModel = historyViewModel)
                    Screen.Settings -> SettingsScreen(
                        viewModel = settingsViewModel,
                        onNavigateToBenchmark = onNavigateToBenchmark,
                        onNavigateToPrivacyPolicy = onNavigateToPrivacyPolicy,
                        onNavigateToTermsOfUse = onNavigateToTermsOfUse,
                        onNavigateToHelpCenter = onNavigateToHelpCenter,
                        onNavigateToLicense = onNavigateToLicense,
                        onNavigateToAbout = onNavigateToAbout,
                        onNavigateToAutoReply = onNavigateToAutoReply,
                        onNavigateToNotificationHistory = onNavigateToNotificationHistory,
                        onNavigateToPermissions = onNavigateToPermissions,
                        onNavigateToCrashLog = onNavigateToCrashLog,
                        onNavigateToRoutines = onNavigateToRoutines
                    )
                }
            }
        }
    }

    AuroraMoreSheet(
        visible = moreSheetVisible,
        onDismiss = { moreSheetVisible = false },
        onNavigate = { screen ->
            moreSheetVisible = false
            currentTab = screen
        },
        onNavigateToRoutines = {
            moreSheetVisible = false
            onNavigateToRoutines()
        },
        onNavigateToPermissions = {
            moreSheetVisible = false
            onNavigateToPermissions()
        },
        onNavigateToNotificationHistory = {
            moreSheetVisible = false
            onNavigateToNotificationHistory()
        },
        routineCount = 3,
        pendingCount = 2
    )
}
