package dev.claudiverse.core

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

/** Breadcrumbs start at the allowed root: above it the agent refuses (403). */
class CrumbsTest {
    private val roots = listOf("/Users/me/Documents/projects", "/Users/me/Documents/projects/apps-repo/upstream")

    @Test
    fun crumbsStartAtTheRoot() {
        assertEquals(
            listOf(
                Crumb("/Users/me/Documents/projects", "/Users/me/Documents/projects"),
                Crumb("lorem-project", "/Users/me/Documents/projects/lorem-project"),
                Crumb("tools", "/Users/me/Documents/projects/lorem-project/tools"),
            ),
            crumbs("/Users/me/Documents/projects/lorem-project/tools", roots),
        )
    }

    @Test
    fun theDeepestRootHoldingThePathWins() {
        assertEquals(
            listOf(Crumb("/Users/me/Documents/projects/apps-repo/upstream", "/Users/me/Documents/projects/apps-repo/upstream"), Crumb("main", "/Users/me/Documents/projects/apps-repo/upstream/main")),
            crumbs("/Users/me/Documents/projects/apps-repo/upstream/main", roots),
        )
    }

    @Test
    fun aSiblingWithTheRootAsPrefixIsNotUnderIt() {
        assertEquals("/", crumbs("/Users/me/Documents/projects-old/x", roots).first().path.take(1))
        assertEquals(Crumb("Users", "/Users"), crumbs("/Users/me/Documents/projects-old/x", roots).first())
    }

    @Test
    fun withNoRootEverySegmentIsACrumb() {
        assertEquals(
            listOf(Crumb("srv", "/srv"), Crumb("app", "/srv/app")),
            crumbs("/srv/app", emptyList()),
        )
    }

    @Test
    fun aRecentFolderOutsideTheWhitelistIsNoSpawnPath() {
        val inside = RecentDir("/Users/me/Documents/projects/lorem-project", null, 1)
        val outside = RecentDir("/Users/me/Documents/work/ipsum-api", null, 1)
        val state = NewSeatState(host = "my-mac", tab = FolderTab.Recent, roots = roots.take(1), title = "t")
        assertNull(state.copy(recents = listOf(outside)).path)
        assertEquals(inside.path, state.copy(recents = listOf(outside, inside), recentIndex = 1).path)
        assertNull(state.copy(recents = listOf(inside), roots = emptyList()).path)
    }
}
