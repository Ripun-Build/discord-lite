package com.discordlite.app.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.VisibilityThreshold
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.discordlite.app.ui.auth.SignInScreen
import com.discordlite.app.ui.auth.SignUpScreen
import com.discordlite.app.ui.auth.VerifyEmailScreen
import com.discordlite.app.ui.auth.WelcomeScreen
import com.discordlite.app.ui.chats.ChannelScreen
import com.discordlite.app.ui.chats.ConversationScreen
import com.discordlite.app.ui.home.HomeScreen
import com.discordlite.app.ui.profile.SettingsScreen
import com.discordlite.designsystem.theme.DlMotion
import com.discordlite.designsystem.theme.DlTheme
import kotlinx.serialization.Serializable

@Serializable object WelcomeRoute
@Serializable object SignUpRoute
@Serializable object SignInRoute
@Serializable data class VerifyRoute(val email: String)
@Serializable object HomeRoute
@Serializable data class ConversationRoute(val chatId: String)
@Serializable data class ChannelRoute(val serverId: String, val channelId: String)
@Serializable object SettingsRoute

private typealias Enter = AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition
private typealias Exit = AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition

/**
 * Screens push in from the right and pop back out to the right — the same path both ways — while
 * the screen underneath shifts a quarter-width, as on iOS. The system back gesture drives these
 * transitions directly (predictive back), so a pop can be scrubbed and cancelled mid-way.
 * With reduced motion the slides become cross-fades.
 */
@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    val reduced = DlTheme.reducedMotion
    val slide = DlMotion.spring<IntOffset>(1f, 0.42f, IntOffset.VisibilityThreshold)
    val fade = DlMotion.smooth<Float>()

    val enter: Enter = { if (reduced) fadeIn(fade) else slideInHorizontally(slide) { it } }
    val exit: Exit = { if (reduced) fadeOut(fade) else slideOutHorizontally(slide) { -it / 4 } + fadeOut(fade, targetAlpha = 0.7f) }
    val popEnter: Enter = { if (reduced) fadeIn(fade) else slideInHorizontally(slide) { -it / 4 } + fadeIn(fade, initialAlpha = 0.7f) }
    val popExit: Exit = { if (reduced) fadeOut(fade) else slideOutHorizontally(slide) { it } }

    NavHost(
        navController = navController,
        startDestination = WelcomeRoute,
        enterTransition = enter,
        exitTransition = exit,
        popEnterTransition = popEnter,
        popExitTransition = popExit,
    ) {
        composable<WelcomeRoute> {
            WelcomeScreen(
                onCreateAccount = { navController.navigate(SignUpRoute) },
                onSignIn = { navController.navigate(SignInRoute) },
            )
        }
        composable<SignUpRoute> {
            SignUpScreen(onBack = navController::popBackStack, onContinue = { navController.navigate(VerifyRoute(it)) })
        }
        composable<VerifyRoute> { entry ->
            VerifyEmailScreen(
                email = entry.toRoute<VerifyRoute>().email,
                onBack = navController::popBackStack,
                onVerified = { navController.enterHome() },
            )
        }
        composable<SignInRoute> {
            SignInScreen(onBack = navController::popBackStack, onSignedIn = { navController.enterHome() })
        }
        composable<HomeRoute>(
            // Arriving after sign-in: the app "opens up" rather than sliding sideways.
            enterTransition = { if (initialState.destination.route?.contains("Home") == true) enter() else fadeIn(fade) + scaleIn(DlMotion.smooth(), initialScale = 0.94f) },
        ) {
            HomeScreen(
                onOpenChat = { navController.navigate(ConversationRoute(it)) },
                onOpenChannel = { server, channel -> navController.navigate(ChannelRoute(server, channel)) },
                onOpenSettings = { navController.navigate(SettingsRoute) },
            )
        }
        composable<ConversationRoute> { entry ->
            ConversationScreen(entry.toRoute<ConversationRoute>().chatId, onBack = navController::popBackStack)
        }
        composable<ChannelRoute> { entry ->
            val route = entry.toRoute<ChannelRoute>()
            ChannelScreen(route.serverId, route.channelId, onBack = navController::popBackStack)
        }
        composable<SettingsRoute> {
            SettingsScreen(
                onBack = navController::popBackStack,
                onSignOut = {
                    navController.navigate(WelcomeRoute) { popUpTo(0) { inclusive = true } }
                },
            )
        }
    }
}

private fun NavHostController.enterHome() {
    navigate(HomeRoute) { popUpTo(WelcomeRoute) { inclusive = true } }
}
