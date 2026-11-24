package com.ai.assistance.operit.features.orbit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OrbitVoiceTextTest {
    @get:Rule val temp = TemporaryFolder()

    // As saveBytes does: the digest is filled with the recording's bytes as they are written.
    private fun save(audio: File, text: String) =
        OrbitVoiceText.save(audio, text, temp.root, OrbitVoiceText.digest().apply { update(audio.readBytes()) })

    private fun voiceDir() = File(temp.root, "files/voice").apply { mkdirs() }
    // The chat's markdown holds a recording as a percent-encoded file:// url.
    private fun urlOf(f: File) = "file://" + f.absolutePath.replace('\\', '/')
        .replace("第一条 语音", "%E7%AC%AC%E4%B8%80%E6%9D%A1%20%E8%AF%AD%E9%9F%B3")

    @Test fun wordsKeptWhenMadeAreReadBackFromTheMarkdownUrl() {
        val voice = File(voiceDir(), "第一条 语音.wav").apply { writeBytes(byteArrayOf(1)) }
        save(voice, " 雨晴，早上好。 ")
        assertEquals("雨晴，早上好。", OrbitVoiceText.read(urlOf(voice), temp.root))
    }

    @Test fun aRestoredRecordingWithANewTimeKeepsItsWords() {
        val voice = File(voiceDir(), "clip.wav").apply { writeBytes(byteArrayOf(1, 2)) }
        save(voice, "还在")
        voice.setLastModified(voice.lastModified() - 86_400_000L)
        assertEquals("还在", OrbitVoiceText.read(urlOf(voice), temp.root))
    }

    @Test fun anotherRecordingOfTheSameNameOrADeletedOneHasNoWords() {
        val voice = File(voiceDir(), "clip.wav").apply { writeBytes(byteArrayOf(1)) }
        save(voice, "旧的话")
        voice.writeBytes(byteArrayOf(2))
        assertNull(OrbitVoiceText.read(urlOf(voice), temp.root))
        save(voice, "新的话")
        assertEquals("新的话", OrbitVoiceText.read(urlOf(voice), temp.root))
        OrbitVoiceText.forget(voice, temp.root)
        assertNull(OrbitVoiceText.read(urlOf(voice), temp.root))
    }

    @Test fun readingStopsWhenTheBarIsGone() {
        val voice = File(voiceDir(), "gone.wav").apply { writeBytes(byteArrayOf(1)) }
        save(voice, "话")
        assertNull(OrbitVoiceText.read(urlOf(voice), temp.root) { false })
    }

    @Test fun podcastLinesAreOneSpeakerARow() {
        assertEquals("A：今天聊什么？\nB：聊聊计划。", OrbitVoiceText.of(listOf("A" to "今天聊什么？", "B" to "聊聊计划。")))
        assertEquals("只有一句", OrbitVoiceText.of(listOf("voice" to "只有一句")))
    }

    @Test fun noWordsOutsideTheVoiceFolderOrOnTheWeb() {
        val song = File(File(temp.root, "files/music").apply { mkdirs() }, "song.mp3").apply { writeBytes(byteArrayOf(1)) }
        save(song, "歌词")
        assertNull(OrbitVoiceText.read(urlOf(song), temp.root))
        assertNull(OrbitVoiceText.read("https://example.com/a.mp3", temp.root))
        // A voice folder outside the app's files is not Orbit's.
        val elsewhere = File(temp.newFolder("app"), "x")
        val voice = File(voiceDir(), "v.wav").apply { writeBytes(byteArrayOf(1)) }
        save(voice, "话")
        assertNull(OrbitVoiceText.read(urlOf(voice), elsewhere.apply { mkdirs() }))
    }
}
