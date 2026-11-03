package com.timeagent.ui.navigation

import android.net.Uri
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.*
import kotlinx.coroutines.flow.first

// Voice is an action; it never occupies a destination or a saved tab stack.
enum class AppTab(val index: Int, val route: String) {
    TODAY(0, "today"), PLAN(1, "plan"), REVIEW(2, "review"), BUTLER(3, "butler");
    val home get() = "$route/home"
}

@Stable
class AppNavigator(val controller: NavHostController, private val reselected: MutableState<IntArray>) {
    private fun activeTab(): AppTab = AppTab.entries.firstOrNull { tab ->
        controller.currentDestination?.hierarchy?.any { it.route == tab.route } == true
    } ?: AppTab.TODAY

    val currentTab: AppTab
        @Composable get() { controller.currentBackStackEntryAsState().value; return activeTab() }

    val page: String?
        @Composable get() = controller.currentBackStackEntryAsState().value?.arguments?.getString("destination")

    fun revision(tab: AppTab): Int = reselected.value[tab.index]

    suspend fun awaitReady() { controller.currentBackStackEntryFlow.first() }

    fun selectTab(index: Int) {
        val target = AppTab.entries.first { it.index == index }
        if (activeTab() == target) {
            if (controller.currentDestination?.route != target.home) controller.popBackStack(target.home, false)
            else reselected.value = reselected.value.copyOf().also { it[target.index]++ }
            return
        }
        controller.navigate(target.route) {
            popUpTo(controller.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    private fun owner(destination: String): AppTab = when {
        destination in setOf("tasks", "projects", "recurring", "deadlines", "goals") || destination.startsWith("project:") -> AppTab.PLAN
        destination == "history" -> AppTab.REVIEW
        else -> AppTab.BUTLER
    }

    fun open(destination: String) {
        val target = owner(destination)
        if (activeTab() != target) selectTab(target.index)
        if (controller.currentBackStackEntry?.arguments?.getString("destination") == destination) return
        controller.navigate("${target.route}/page/${Uri.encode(destination)}")
    }

    fun openDeepLink(destination: String) {
        val target = owner(destination)
        if (activeTab() != target) selectTab(target.index)
        controller.popBackStack(target.home, false)
        open(destination)
    }

    fun returnToRoot(index: Int) {
        val target = AppTab.entries.first { it.index == index }
        if (activeTab() != target) selectTab(index)
        controller.popBackStack(target.home, false)
    }

    fun resetToToday() = returnToRoot(AppTab.TODAY.index)

    fun close(): Boolean = if (controller.currentBackStackEntry?.arguments?.containsKey("destination") == true)
        controller.popBackStack() else false
}

@Composable
fun rememberAppNavigator(): AppNavigator {
    val controller = rememberNavController()
    val revisions = rememberSaveable { mutableStateOf(IntArray(AppTab.entries.size)) }
    return remember(controller) { AppNavigator(controller, revisions) }
}

@Composable
fun AppNavHost(navigator: AppNavigator, modifier: Modifier = Modifier, motionMillis: Int = 200,
    rootContent: @Composable (Int, Int) -> Unit, pageContent: @Composable (String) -> Unit) {
    NavHost(navController = navigator.controller, startDestination = AppTab.TODAY.route, modifier = modifier,
        enterTransition = { fadeIn(tween(motionMillis)) }, exitTransition = { fadeOut(tween(motionMillis)) }) {
        AppTab.entries.forEach { tab ->
            navigation(startDestination = tab.home, route = tab.route) {
                composable(tab.home) { rootContent(tab.index, navigator.revision(tab)) }
                composable("${tab.route}/page/{destination}", arguments = listOf(navArgument("destination") { type = NavType.StringType })) { entry ->
                    pageContent(requireNotNull(entry.arguments?.getString("destination")))
                }
            }
        }
    }
}
