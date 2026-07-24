package com.ai.assistance.operit.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM unit tests for [UserPreferencesManager.resolveAppInternalAvatarUri].
 *
 * Since the method uses [java.net.URI], it is fully testable without Robolectric.
 */
class AvatarUriResolutionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val filesDir: File
        get() = tempFolder.root

    // ========== null / blank ==========

    @Test
    fun `null rawUri returns null`() {
        assertNull(UserPreferencesManager.resolveAppInternalAvatarUri(null, filesDir))
    }

    @Test
    fun `blank rawUri returns null`() {
        assertNull(UserPreferencesManager.resolveAppInternalAvatarUri("   ", filesDir))
    }

    @Test
    fun `empty rawUri returns null`() {
        assertNull(UserPreferencesManager.resolveAppInternalAvatarUri("", filesDir))
    }

    // ========== asset passthrough ==========

    @Test
    fun `asset uri returns itself`() {
        val uri = "file:///android_asset/operit.png"
        assertEquals(uri, UserPreferencesManager.resolveAppInternalAvatarUri(uri, filesDir))
    }

    @Test
    fun `asset uri with subdirectory returns itself`() {
        val uri = "file:///android_asset/custom/avatar.png"
        assertEquals(uri, UserPreferencesManager.resolveAppInternalAvatarUri(uri, filesDir))
    }

    // ========== content passthrough ==========

    @Test
    fun `content scheme uri returns itself`() {
        val uri = "content://com.example.provider/avatar/123"
        assertEquals(uri, UserPreferencesManager.resolveAppInternalAvatarUri(uri, filesDir))
    }

    @Test
    fun `android resource scheme uri returns itself`() {
        val uri = "android.resource://com.example/drawable/avatar"
        assertEquals(uri, UserPreferencesManager.resolveAppInternalAvatarUri(uri, filesDir))
    }

    // ========== existing file preservation ==========

    @Test
    fun `existing file in current path returns itself`() {
        val file = tempFolder.newFile("avatar_abc123_uuid_value.png")
        val uri = file.toURI().toString()
        assertEquals(uri, UserPreferencesManager.resolveAppInternalAvatarUri(uri, filesDir))
    }

    @Test
    fun `existing group avatar file in current path returns itself`() {
        val file = tempFolder.newFile("group_avatar_grp_uuid_value.png")
        val uri = file.toURI().toString()
        assertEquals(uri, UserPreferencesManager.resolveAppInternalAvatarUri(uri, filesDir))
    }

    // ========== cross-package relocation ==========

    @Test
    fun `nonexistent file relocated to current filesDir`() {
        val file = tempFolder.newFile("avatar_abc_uuid_test.png")
        val fileName = file.name
        val oldPath = "/data/user/0/com.ai.assistance.operit/files/$fileName"
        val oldUri = "file://$oldPath"
        assert(!File(oldPath).exists()) { "Old path should not exist" }

        val result = UserPreferencesManager.resolveAppInternalAvatarUri(oldUri, filesDir)
        assertEquals(file.toURI().toString(), result)
    }

    @Test
    fun `nonexistent file with no counterpart in current dir returns null`() {
        val oldUri = "file:///data/user/0/com.old.app/files/avatar_nonexistent_uuid.png"
        val fileInDir = File(filesDir, "avatar_nonexistent_uuid.png")
        assert(!fileInDir.exists())

        assertNull(UserPreferencesManager.resolveAppInternalAvatarUri(oldUri, filesDir))
    }

    // ========== filename pattern validation ==========

    @Test
    fun `non-avatar filename returns null`() {
        val uri = "file:///data/user/0/com.example/files/some_random_file.txt"
        assertNull(UserPreferencesManager.resolveAppInternalAvatarUri(uri, filesDir))
    }

    @Test
    fun `avatar filename without extension returns null`() {
        val uri = "file:///data/user/0/com.example/files/avatar_test"
        assertNull(UserPreferencesManager.resolveAppInternalAvatarUri(uri, filesDir))
    }

    // ========== path traversal rejection ==========

    @Test
    fun `path with parent directory traversal returns null`() {
        val uri = "file:///data/../etc/passwd"
        assertNull(UserPreferencesManager.resolveAppInternalAvatarUri(uri, filesDir))
    }

    // ========== URI with hyphens and underscores ==========

    @Test
    fun `avatar filename with hyphens and underscores is accepted`() {
        val file = tempFolder.newFile("avatar_my-card-v2_uuid_hash.png")
        val uri = file.toURI().toString()
        assertEquals(uri, UserPreferencesManager.resolveAppInternalAvatarUri(uri, filesDir))
    }

    // ========== default character avatar constant ==========

    @Test
    fun `default character avatar constant is asset uri`() {
        assertEquals(
            "file:///android_asset/operit.png",
            UserPreferencesManager.DEFAULT_CHARACTER_AVATAR_URI,
        )
    }
}
