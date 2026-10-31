package com.timeagent.runtime

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Filesystem boundary: archive names and link targets must stay inside the guest rootfs. */
class GuestPathsTest {

    @Test
    fun rejectsAbsoluteTraversalBackslashAndNulMembers() {
        assertThrows(IOException::class.java) { GuestPaths.member("/etc/passwd", 0) }
        assertThrows(IOException::class.java) { GuestPaths.member("usr/../../etc/passwd", 0) }
        assertThrows(IOException::class.java) { GuestPaths.member("usr\\..\\etc", 0) }
        assertThrows(IOException::class.java) { GuestPaths.member("usr/bin/no\u0000de", 0) }
    }

    @Test
    fun normalizesDotSegmentsAndStripsLeadingComponents() {
        assertEquals("usr/bin/node", GuestPaths.member("./usr/bin/node", 0))
        assertEquals("usr/bin/node", GuestPaths.member("glibc-python/usr/bin/node", 1))
        assertNull(GuestPaths.member(".", 0))
        assertNull(GuestPaths.member("glibc-python", 1))
        assertThrows(IOException::class.java) { GuestPaths.member("a/b", -1) }
    }

    @Test
    fun allowsGuestAbsoluteSymlinksButRejectsRelativeEscape() {
        // /usr/local/... is resolved by proot inside the rootfs, never against the host root.
        assertEquals("/usr/local/lib/node_modules", GuestPaths.symlink("root/time-agent/node_modules", "/usr/local/lib/node_modules"))
        assertEquals("../../usr/share", GuestPaths.symlink("root/a/b/link", "../../usr/share"))
        assertThrows(IOException::class.java) { GuestPaths.symlink("root/link", "../../etc/passwd") }
        assertThrows(IOException::class.java) { GuestPaths.symlink("root/link", "../../../etc/passwd") }
        assertThrows(IOException::class.java) { GuestPaths.symlink("root/link", "..\\..\\etc") }
    }

    @Test
    fun hardlinkTargetsResolveFromTheGuestRootAndCannotEscape() {
        assertEquals("usr/local/bin/node", GuestPaths.hardlink("root/bin/node", "usr/local/bin/node", 0))
        assertEquals("usr/bin/tool", GuestPaths.hardlink("root/tool", "/usr/bin/tool", 0))
        assertEquals("usr/bin/tool", GuestPaths.hardlink("root/tool", "pkg/usr/bin/tool", 1))
        assertThrows(IOException::class.java) { GuestPaths.hardlink("root/tool", "../../etc/passwd", 0) }
        assertThrows(IOException::class.java) { GuestPaths.hardlink("root/tool", "pkg", 1) }
    }
}
