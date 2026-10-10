package dev.claudiverse.app.ui

import androidx.compose.material.icons.filled.Badge
import dev.claudiverse.app.profiles.ProfileEditorViewModel
import dev.claudiverse.app.profiles.ProfilesViewModel
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Badge
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.rememberCoroutineScope
import dev.claudiverse.app.spawn.NewSeatViewModel
import dev.claudiverse.core.SpawnNotice
import kotlinx.coroutines.launch
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.claudiverse.app.AppContainer
import dev.claudiverse.app.seat.SeatViewModel
import dev.claudiverse.app.update.UpdateState
import dev.claudiverse.app.vpn.VpnProfileInfo

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("seats", "Seats", Icons.Filled.AccountCircle),
    Tab("forum", "Forum", Icons.Filled.Email),
    Tab("tasks", "Tasks", Icons.AutoMirrored.Filled.List),
    Tab("pool", "Pool", Icons.Filled.Info),
    Tab("connection", "Connection", Icons.Filled.Lock),
)

/** What the activity does for the UI: things that need an Activity to launch. */
class VpnActions(
    val profile: VpnProfileInfo?,
    val import: () -> Unit,
    val connect: () -> Unit,
    val disconnect: () -> Unit,
    val saveCredentials: (String, String) -> Unit,
)

/** A seat to open from outside the navigation, e.g. a "needs you" notification. */
data class SeatLink(val id: Long, val title: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(app: AppContainer, openSeat: MutableStateFlow<SeatLink?>, vpnActions: VpnActions) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val config by app.settings.config.collectAsStateWithLifecycle()
    val vpn by app.vpn.status.collectAsStateWithLifecycle()
    val updateState by app.updates.status.collectAsStateWithLifecycle()
    val topLevel = TABS.any { it.route == route }
    val start = if (config.isComplete) "seats" else "connection"
    // Session info for the open seat: its title in the top bar opens it.
    var seatInfo by remember { mutableStateOf(false) }
    val onSeat = route?.startsWith("seat/") == true
    LaunchedEffect(route) { seatInfo = false }
    val link by openSeat.collectAsStateWithLifecycle()
    LaunchedEffect(link) {
        val l = link ?: return@LaunchedEffect
        // Reuse the top entry only when it already shows this seat. launchSingleTop
        // matches the route pattern, not the id: from another seat's screen it kept
        // that seat's view model under the new title, so the notification opened
        // the wrong transcript and never resolved the request.
        val top = nav.currentBackStackEntry
        val showing = top?.destination?.route?.startsWith("seat/") == true && top.arguments?.getLong("id") == l.id
        if (!showing) nav.navigate("seat/${l.id}?title=${Uri.encode(l.title)}")
        openSeat.value = null
    }

    // New seat is a full-screen step (design): no app bar or tabs while it is open.
    val fullScreen = route == "new-seat"
    val snackbar = remember { SnackbarHostState() }
    val spawnNotice by app.spawns.notice.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(spawnNotice) {
        val n = spawnNotice ?: return@LaunchedEffect
        val result = when (n) {
            is SpawnNotice.Pending -> snackbar.showSnackbar("${n.op.doing} “${n.title}” on ${n.host}…", duration = SnackbarDuration.Indefinite, withDismissAction = true)
            is SpawnNotice.Started -> snackbar.showSnackbar("“${n.title}” ${n.op.done} on ${n.host}", actionLabel = "View", duration = SnackbarDuration.Long)
            is SpawnNotice.Failed -> snackbar.showSnackbar("“${n.title}” ${n.op.notDone}: ${n.error}", withDismissAction = true, duration = SnackbarDuration.Long)
        }
        if (result == SnackbarResult.ActionPerformed && n is SpawnNotice.Started) {
            // The event names the row the seat registered as (resumed_as / started_as);
            // from an older server, a new seat is found as the newest one with its title.
            scope.launch {
                val id = n.sessionId
                    ?: runCatching { app.api.seats() }.getOrNull()?.filter { it.title == n.title }?.maxByOrNull { it.id }?.id
                    ?: return@launch
                nav.navigate("seat/$id?title=${Uri.encode(n.title)}")
            }
        }
        if (result == SnackbarResult.Dismissed && n !is SpawnNotice.Pending) app.spawns.dismiss()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (route == "seats") ExtendedFloatingActionButton(
                onClick = { nav.navigate("new-seat") },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New seat") },
            )
        },
        topBar = {
            if (!fullScreen) TopAppBar(
                title = {
                    Text(
                        titleFor(route, entry?.arguments), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        // The whole title area, not just the text: a short name is a small target.
                        modifier = if (onSeat) Modifier.fillMaxWidth().clickable { seatInfo = true } else Modifier,
                    )
                },
                navigationIcon = {
                    if (!topLevel) IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (route == "seats") IconButton(onClick = { nav.navigate("profiles") }) {
                        Icon(Icons.Filled.Badge, contentDescription = "Profiles")
                    }
                    Box(Modifier.padding(end = 12.dp).clickable { nav.navigateTab("connection") }) { VpnPill(vpn) }
                },
            )
        },
        bottomBar = {
            if (!fullScreen) NavigationBar {
                TABS.forEach { tab ->
                    val badge = tab.route == "connection" && updateState is UpdateState.Available
                    NavigationBarItem(
                        selected = tabFor(route) == tab.route,
                        onClick = {
                            // Re-tapping the current tab goes back to its top level (a chat
                            // back to the seat list); another tab restores where it was left.
                            if (tabFor(route) == tab.route) {
                                if (!nav.popBackStack(tab.route, inclusive = false)) nav.navigateTab(tab.route)
                            } else nav.navigateTab(tab.route)
                        },
                        // An update waiting is flagged on the Connection tab, where its card is.
                        icon = { BadgedBox(badge = { if (badge) Badge() }) { Icon(tab.icon, contentDescription = tab.label) } },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        // consumeWindowInsets: the bottom bar's padding already covers part of
        // the keyboard, so a screen's imePadding adds only the rest. Without it
        // the composer floats a bottom-bar height above the keyboard.
        NavHost(nav, startDestination = start, modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
            composable("profiles") {
                ProfilesScreen(
                    viewModel { ProfilesViewModel(app) },
                    onOpen = { id -> nav.navigate("profile/$id") },
                    onNew = { nav.navigate("profile/$NEW_PROFILE") },
                )
            }
            composable("profile/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                val id = e.arguments!!.getLong("id").takeIf { it != NEW_PROFILE }
                ProfileEditorScreen(
                    viewModel { ProfileEditorViewModel(app, id) },
                    onClose = { nav.popBackStack() },
                    onOpenSeat = { seat, name -> nav.navigate("seat/$seat?title=${Uri.encode(name)}") },
                    // A created profile opens as itself, in place of the empty form.
                    onCreated = { newId -> nav.navigate("profile/$newId") { popUpTo("profile/{id}") { inclusive = true } } },
                )
            }
            composable("new-seat") {
                NewSeatScreen(viewModel { NewSeatViewModel(app) }, onClose = { nav.popBackStack() })
            }
            composable("seats") {
                SeatsScreen(viewModel { RemoteViewModel(pollMs = 10_000) { app.api.seats(app.seatsIncludeStopped.value) } }, app.seatsIncludeStopped, app.outbox.items, app.humanRequests.open) { seat ->
                    nav.navigate("seat/${seat.id}?title=${Uri.encode(seat.label)}")
                }
            }
            composable(
                "seat/{id}?title={title}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }, navArgument("title") { nullable = true }),
            ) { e ->
                val id = e.arguments!!.getLong("id")
                SeatScreen(viewModel { SeatViewModel(app, id) }, showInfo = seatInfo, onShowInfo = { seatInfo = it })
            }
            composable("forum") {
                ChannelsScreen(viewModel { RemoteViewModel(pollMs = null) { app.api.channels() } }) { ch ->
                    nav.navigate("channel/${ch.slug}")
                }
            }
            composable("channel/{slug}") { e ->
                val slug = e.arguments!!.getString("slug")!!
                ChannelScreen(
                    slug,
                    viewModel { RemoteViewModel(pollMs = 20_000) { app.api.posts(slug) } },
                    onOpenThread = { nav.navigate("thread/$it") },
                    post = { body -> app.api.createPost(slug, body) },
                )
            }
            composable("thread/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                val id = e.arguments!!.getLong("id")
                ThreadScreen(viewModel { RemoteViewModel(pollMs = 20_000) { app.api.thread(id) } }) { root, body ->
                    app.api.createPost(root.channel ?: error("post #${root.id} has no channel"), body, parentId = root.id)
                }
            }
            composable("tasks") { TasksScreen { state -> app.api.tasks(state, fleet = null, limit = 200) } }
            composable("pool") {
                PoolScreen(
                    viewModel {
                        RemoteViewModel(pollMs = 30_000) {
                            PoolView(
                                health = runCatching { app.api.health() }.getOrNull(),
                                pool = app.api.pool(),
                                incidents = runCatching { app.api.incidents() }.getOrNull(),
                            )
                        }
                    },
                    events = app.poolEvents,
                    makePreferred = { id -> app.api.makePreferred(id) },
                )
            }
            composable("connection") {
                ConnectionScreen(
                    vpn = vpn,
                    profile = vpnActions.profile,
                    config = config,
                    onImport = vpnActions.import,
                    onConnect = vpnActions.connect,
                    onDisconnect = vpnActions.disconnect,
                    onSaveCredentials = vpnActions.saveCredentials,
                    onSaveConfig = app.settings::save,
                    requestsSupported = app.humanRequests.supported,
                    updates = app.updates,
                    testServer = { app.api.me().email },
                    account = remember {
                        AccountActions(
                            me = { app.api.me() },
                            signIn = { email, password ->
                                app.api.signIn(email, password, client = deviceClientName(), scope = "super").also { signed ->
                                    app.settings.save(app.settings.config.value.copy(token = signed.token))
                                }
                            },
                            // Forget the token here even when the server refuses to revoke it
                            // (one minted on the server is never revoked by a client).
                            signOut = {
                                try {
                                    app.api.signOut()
                                } finally {
                                    app.settings.save(app.settings.config.value.copy(token = ""))
                                }
                            },
                        )
                    },
                )
            }
        }
    }
}

private fun androidx.navigation.NavHostController.navigateTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

/** The tab a route belongs to, so a detail screen keeps its tab lit. */
private fun tabFor(route: String?): String? = when {
    route == null -> null
    route.startsWith("seat/") || route == "profiles" || route.startsWith("profile/") -> "seats"
    route.startsWith("channel/") || route.startsWith("thread/") -> "forum"
    else -> route
}

private fun titleFor(route: String?, args: android.os.Bundle?): String = when {
    route == null -> "Claudiverse"
    route.startsWith("seat/") -> args?.getString("title") ?: "Seat"
    route.startsWith("channel/") -> "#" + args?.getString("slug").orEmpty()
    route.startsWith("thread/") -> "Thread #" + (args?.getLong("id") ?: "")
    route == "profiles" -> "Profiles"
    route.startsWith("profile/") -> if (args?.getLong("id") == NEW_PROFILE) "New profile" else "Profile"
    else -> TABS.firstOrNull { it.route == route }?.label ?: "Claudiverse"
}

/** How this phone is named in the server's token list: "Android Pixel 10 Pro". Only the characters the server takes. */
private fun deviceClientName(): String =
    "Android ${android.os.Build.MODEL}".replace(Regex("[^A-Za-z0-9 ._-]"), "").take(64).trim()

/** The profile route's id for the empty form. */
private const val NEW_PROFILE = -1L
