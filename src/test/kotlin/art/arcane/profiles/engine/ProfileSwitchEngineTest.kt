package art.arcane.profiles.engine

import art.arcane.profiles.model.Profile
import com.intellij.notification.NotificationType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class ProfileSwitchEngineTest {

    @Test
    fun `active profile remains unchanged while a switch is in progress`() = runBlocking {
        val closeOld = CompletableDeferred<Unit>()
        val windows = FakeWindows(
            existingPaths = setOf("/old", "/next"),
            initialProjects = listOf(FakeProject("old", "/old")),
            closeGates = mutableMapOf("/old" to closeOld),
        )
        val store = FakeStore(activeProfileName = "Old", profileNames = setOf("Old", "Next"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = engine(scope, windows, store)

        try {
            engine.requestSwitch(Profile("Next", null, null, listOf("/next")))

            awaitUntil {
                windows.hasOpen("/next") &&
                    engine.status.value is SwitchStatus.Switching &&
                    (engine.status.value as SwitchStatus.Switching).closingCount == 1
            }

            assertEquals("Old", store.activeProfileName)
            assertTrue(windows.hasOpen("/old"))

            closeOld.complete(Unit)
            awaitUntil { engine.status.value == SwitchStatus.Idle("Next") }

            assertEquals("Next", store.activeProfileName)
            assertFalse(windows.hasOpen("/old"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `active profile changes only after final convergence`() = runBlocking {
        val windows = FakeWindows(
            existingPaths = setOf("/old", "/primary", "/secondary"),
            initialProjects = listOf(FakeProject("old", "/old")),
        )
        val store = FakeStore(activeProfileName = "Old", profileNames = setOf("Old", "Next"))
        val notifications = mutableListOf<Pair<String, NotificationType>>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = engine(scope, windows, store, notifications)

        try {
            engine.requestSwitch(Profile("Next", null, null, listOf("/primary", "/secondary")))

            awaitUntil { engine.status.value == SwitchStatus.Idle("Next") }

            assertEquals("Next", store.activeProfileName)
            assertTrue(windows.hasOpen("/primary"))
            assertTrue(windows.hasOpen("/secondary"))
            assertFalse(windows.hasOpen("/old"))
            assertTrue(notifications.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `secondary projects wait until extras close after primary is focused`() = runBlocking {
        val closeOld = CompletableDeferred<Unit>()
        val windows = FakeWindows(
            existingPaths = setOf("/old", "/primary", "/secondary"),
            initialProjects = listOf(FakeProject("old", "/old")),
            closeGates = mutableMapOf("/old" to closeOld),
        )
        val store = FakeStore(activeProfileName = "Old", profileNames = setOf("Old", "Next"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = engine(scope, windows, store)

        try {
            engine.requestSwitch(Profile("Next", null, null, listOf("/primary", "/secondary")))

            awaitUntil {
                windows.hasOpen("/primary") &&
                    (engine.status.value as? SwitchStatus.Switching)?.closingCount == 1
            }

            assertFalse(windows.hasOpen("/secondary"))
            assertEquals("Old", store.activeProfileName)

            closeOld.complete(Unit)
            awaitUntil { engine.status.value == SwitchStatus.Idle("Next") }

            assertTrue(windows.hasOpen("/secondary"))
            assertFalse(windows.hasOpen("/old"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `extra projects start closing as a batch before secondary projects open`() = runBlocking {
        val closeOldA = CompletableDeferred<Unit>()
        val closeOldB = CompletableDeferred<Unit>()
        val windows = FakeWindows(
            existingPaths = setOf("/old-a", "/old-b", "/primary", "/secondary"),
            initialProjects = listOf(
                FakeProject("old-a", "/old-a"),
                FakeProject("old-b", "/old-b"),
            ),
            closeGates = mutableMapOf(
                "/old-a" to closeOldA,
                "/old-b" to closeOldB,
            ),
        )
        val store = FakeStore(activeProfileName = "Old", profileNames = setOf("Old", "Next"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = engine(scope, windows, store)

        try {
            engine.requestSwitch(Profile("Next", null, null, listOf("/primary", "/secondary")))

            awaitUntil {
                windows.closeStarted("/old-a") &&
                    windows.closeStarted("/old-b") &&
                    (engine.status.value as? SwitchStatus.Switching)?.closingCount == 2
            }

            assertFalse(windows.hasOpen("/secondary"))
            assertEquals("Old", store.activeProfileName)

            closeOldA.complete(Unit)
            closeOldB.complete(Unit)
            awaitUntil { engine.status.value == SwitchStatus.Idle("Next") }

            assertTrue(windows.hasOpen("/secondary"))
            assertFalse(windows.hasOpen("/old-a"))
            assertFalse(windows.hasOpen("/old-b"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `failed secondary opens report non-convergence without marking active`() = runBlocking {
        val windows = FakeWindows(
            existingPaths = setOf("/old", "/primary", "/secondary"),
            initialProjects = listOf(FakeProject("old", "/old")),
            openFailures = setOf("/secondary"),
        )
        val store = FakeStore(activeProfileName = "Old", profileNames = setOf("Old", "Next"))
        val notifications = mutableListOf<Pair<String, NotificationType>>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = engine(scope, windows, store, notifications)

        try {
            engine.requestSwitch(Profile("Next", null, null, listOf("/primary", "/secondary")))

            awaitUntil { notifications.any { it.first.contains("did not fully apply") } }
            awaitUntil { engine.status.value == SwitchStatus.Idle("Old") }

            assertEquals("Old", store.activeProfileName)
            assertTrue(windows.hasOpen("/primary"))
            assertFalse(windows.hasOpen("/secondary"))
            assertFalse(windows.hasOpen("/old"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `failed primary open leaves extras open and active unchanged`() = runBlocking {
        val windows = FakeWindows(
            existingPaths = setOf("/old", "/primary", "/secondary"),
            initialProjects = listOf(FakeProject("old", "/old")),
            openFailures = setOf("/primary"),
        )
        val store = FakeStore(activeProfileName = "Old", profileNames = setOf("Old", "Next"))
        val notifications = mutableListOf<Pair<String, NotificationType>>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = engine(scope, windows, store, notifications)

        try {
            engine.requestSwitch(Profile("Next", null, null, listOf("/primary", "/secondary")))

            awaitUntil { notifications.any { it.first.contains("did not fully apply") } }
            awaitUntil { engine.status.value == SwitchStatus.Idle("Old") }

            assertEquals("Old", store.activeProfileName)
            assertTrue(windows.hasOpen("/old"))
            assertFalse(windows.hasOpen("/primary"))
            assertTrue(windows.closedKeys.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `rapid switches do not let a superseded profile mark itself active`() = runBlocking {
        val closeOld = CompletableDeferred<Unit>()
        val windows = FakeWindows(
            existingPaths = setOf("/old", "/b", "/c"),
            initialProjects = listOf(FakeProject("old", "/old")),
            closeGates = mutableMapOf("/old" to closeOld),
        )
        val store = FakeStore(activeProfileName = "Old", profileNames = setOf("Old", "B", "C"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = engine(scope, windows, store)

        try {
            engine.requestSwitch(Profile("B", null, null, listOf("/b")))
            awaitUntil {
                windows.hasOpen("/b") &&
                    (engine.status.value as? SwitchStatus.Switching)?.targetProfileName == "B"
            }

            engine.requestSwitch(Profile("C", null, null, listOf("/c")))
            awaitUntil {
                windows.hasOpen("/c") &&
                    (engine.status.value as? SwitchStatus.Switching)?.targetProfileName == "C"
            }

            assertEquals("Old", store.activeProfileName)
            closeOld.complete(Unit)
            awaitUntil { engine.status.value == SwitchStatus.Idle("C") }

            assertEquals("C", store.activeProfileName)
            assertFalse(windows.hasOpen("/b"))
            assertFalse(windows.hasOpen("/old"))
            assertTrue(windows.hasOpen("/c"))
        } finally {
            scope.cancel()
        }
    }

    private fun engine(
        scope: CoroutineScope,
        windows: FakeWindows,
        store: FakeStore,
        notifications: MutableList<Pair<String, NotificationType>> = mutableListOf(),
    ): ProfileSwitchEngine =
        ProfileSwitchEngine(
            scope,
            ProfileSwitchDependencies(
                windows = windows,
                store = store,
                notify = { content, type -> notifications.add(content to type) },
                isApplicationDisposed = { false },
                pathExists = { windows.exists(it) },
                markActivity = {},
            ),
        )

    private suspend fun awaitUntil(condition: () -> Boolean) {
        withTimeout(3_000) {
            while (!condition()) delay(10)
        }
    }

    private class FakeStore(
        override var activeProfileName: String?,
        private val profileNames: Set<String>,
    ) : ProfileSwitchStore {
        override fun hasProfile(name: String): Boolean = name in profileNames
    }

    private class FakeProject(
        override val name: String,
        val key: String?,
    ) : ProjectWindowHandle {
        private var disposed = false
        override val isDisposed: Boolean get() = disposed
        fun dispose() {
            disposed = true
        }
    }

    private class FakeWindows(
        private val existingPaths: Set<String>,
        initialProjects: List<FakeProject> = emptyList(),
        private val openFailures: Set<String> = emptySet(),
        private val closeGates: MutableMap<String, CompletableDeferred<Unit>> = mutableMapOf(),
    ) : ProjectWindowGateway {
        private val projects = initialProjects.toMutableList()
        val closedKeys = mutableListOf<String>()
        private val closeStartedKeys = mutableSetOf<String>()

        fun exists(path: Path): Boolean = path.toString() in existingPaths

        fun hasOpen(key: String): Boolean = projects.any { !it.isDisposed && it.key == key }

        fun closeStarted(key: String): Boolean = synchronized(closeStartedKeys) { key in closeStartedKeys }

        override fun liveProjects(): List<ProjectWindowHandle> = projects.filterNot { it.isDisposed }

        override fun keyOf(project: ProjectWindowHandle): String? = (project as? FakeProject)?.key

        override fun openByKey(): Map<String, ProjectWindowHandle> {
            val open = LinkedHashMap<String, ProjectWindowHandle>()
            for (project in projects) {
                if (project.isDisposed) continue
                val key = project.key ?: continue
                open.putIfAbsent(key, project)
            }
            return open
        }

        override suspend fun openInOwnFrameAsync(path: Path): ProjectWindowHandle? {
            val key = path.toString()
            projects.firstOrNull { !it.isDisposed && it.key == key }?.let { return it }
            if (key in openFailures) return null
            val project = FakeProject(path.fileName?.toString() ?: key, key)
            projects.add(project)
            return project
        }

        override suspend fun closeProject(project: ProjectWindowHandle): Boolean {
            val fake = project as? FakeProject ?: return false
            val closeKey = fake.key ?: fake.name
            synchronized(closeStartedKeys) {
                closeStartedKeys.add(closeKey)
            }
            closeGates[closeKey]?.await()
            fake.dispose()
            closedKeys.add(closeKey)
            return true
        }

        override suspend fun focus(project: ProjectWindowHandle) = Unit

        override suspend fun withSwitchProgress(
            project: ProjectWindowHandle,
            title: String,
            action: suspend () -> Unit,
        ) {
            action()
        }
    }
}
