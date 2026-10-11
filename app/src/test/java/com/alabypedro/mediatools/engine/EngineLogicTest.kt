package com.alabypedro.mediatools.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineLogicTest {

    private val videoJson = """
        {
          "id": "abc", "title": "Meu vídeo", "uploader": "Canal", "duration": 100.5,
          "thumbnail": "https://img/x.jpg", "extractor_key": "Youtube", "view_count": 1234,
          "webpage_url": "https://www.youtube.com/watch?v=abc", "is_live": false,
          "formats": [
            {"format_id": "sb0", "ext": "mhtml", "vcodec": "none", "acodec": "none"},
            {"format_id": "140", "ext": "m4a", "vcodec": "none", "acodec": "mp4a.40.2", "abr": 129.5, "tbr": 129.5, "filesize": 1600000},
            {"format_id": "251", "ext": "webm", "vcodec": "none", "acodec": "opus", "abr": 140.1},
            {"format_id": "137", "ext": "mp4", "vcodec": "avc1", "acodec": "none", "height": 1080, "fps": 30, "tbr": 4000, "filesize": 50000000},
            {"format_id": "136", "ext": "mp4", "vcodec": "avc1", "acodec": "none", "height": 720, "fps": 30, "tbr": 2000},
            {"format_id": "18", "ext": "mp4", "vcodec": "avc1", "acodec": "mp4a", "height": 360, "fps": 30, "tbr": 500, "filesize": null}
          ]
        }
    """.trimIndent()

    @Test
    fun parsesVideoInfo() {
        val info = MediaInfo.parse(videoJson, "https://youtu.be/abc")
        assertEquals("Meu vídeo", info.title)
        assertEquals("Canal", info.uploader)
        assertEquals("https://www.youtube.com/watch?v=abc", info.url)
        assertEquals(listOf(1080, 720, 360), info.videoHeights)
        assertTrue(info.hasVideo && info.hasAudio)
        assertFalse(info.isPlaylist)
        assertFalse(info.hasLosslessAudio)
        assertEquals(140, info.bestAudioBitrate)
        // 1080p só vídeo (50 MB) + melhor áudio (opus 140 kbps estimado pela duração)
        val size = info.estimateVideoSize(null)!!
        assertTrue(size > 50_000_000)
        // storyboard (mhtml) não conta como vídeo
        assertFalse(info.formats.first { it.id == "sb0" }.hasVideo)
    }

    @Test
    fun parsesFlatPlaylist() {
        val json = """
            {"_type": "playlist", "title": "Lista", "playlist_count": 3,
             "entries": [{"title": "A", "url": "u1"}, {"title": "B", "url": "u2"}, {"id": "c3", "url": "u3"}]}
        """
        val info = MediaInfo.parse(json, "https://x")
        assertTrue(info.isPlaylist)
        assertEquals(3, info.playlistCount)
        assertEquals("c3", info.entries[2].title)
        // sem formatos: assume vídeo e áudio
        assertTrue(info.hasVideo && info.hasAudio)
    }

    @Test
    fun audioOnlySourceWithoutFormats() {
        val json = """{"id": "1", "title": "Som", "ext": "mp3", "vcodec": "none", "acodec": "mp3", "url": "https://a/x.mp3"}"""
        val info = MediaInfo.parse(json, "https://a/x.mp3")
        assertFalse(info.hasVideo)
        assertTrue(info.hasAudio)
    }

    @Test
    fun videoArgsMp4() {
        val args = Selection(mode = Mode.VIDEO, quality = "720", container = "mp4").toArgs()
        assertEquals(
            listOf("-f", "bv*+ba/b", "--merge-output-format", "mp4", "--remux-video", "mp4",
                "-S", "res:720,fps,ext:mp4:m4a", "--embed-metadata", "--embed-chapters"),
            args,
        )
    }

    @Test
    fun videoArgsOriginalBestNoMetadata() {
        val args = Selection(mode = Mode.VIDEO, quality = "best", container = "original", embedMetadata = false).toArgs()
        assertEquals(listOf("-f", "bv*+ba/b", "-S", "res,fps"), args)
    }

    @Test
    fun audioArgs() {
        assertEquals(
            listOf("-f", "ba/b", "-x", "--audio-format", "mp3", "--audio-quality", "320K"),
            Selection(mode = Mode.AUDIO, audioFormat = "mp3", audioBitrate = "320", embedMetadata = false).toArgs(),
        )
        assertEquals(
            listOf("-f", "ba/b", "-x", "--audio-format", "m4a", "--audio-quality", "0", "-S", "acodec:aac"),
            Selection(mode = Mode.AUDIO, audioFormat = "m4a", audioBitrate = "original", embedMetadata = false).toArgs(),
        )
        assertEquals(
            listOf("-f", "ba/b", "-x", "--audio-format", "best"),
            Selection(mode = Mode.AUDIO, audioFormat = "original", embedMetadata = false).toArgs(),
        )
    }

    @Test
    fun liveSkipsMetadata() {
        assertFalse("--embed-metadata" in Selection().toArgs(isLive = true))
    }

    @Test
    fun labels() {
        assertEquals("1080p · MP4", Selection(quality = "1080").label)
        assertEquals("MP3 · 320 kbps", Selection(mode = Mode.AUDIO).label)
        assertEquals("FLAC", Selection(mode = Mode.AUDIO, audioFormat = "flac").label)
    }

    @Test
    fun stages() {
        assertEquals("Baixando item 2 de 7", Engine.stageFor("[download] Downloading item 2 of 7"))
        assertEquals("Juntando áudio e vídeo", Engine.stageFor("[Merger] Merging formats into \"x.mp4\""))
        assertEquals(null, Engine.stageFor("[download]  12.3% of 10MiB at 1.2MiB/s ETA 00:05"))
    }

    @Test
    fun friendlyErrors() {
        assertEquals("O conteúdo é privado.", Engine.friendlyError("ERROR: [youtube] abc: Private video. Sign in if..."))
        assertEquals("Esse site ou link não é suportado.", Engine.friendlyError("ERROR: Unsupported URL: https://x"))
        assertEquals("algo estranho", Engine.friendlyError("WARNING: x\nERROR: algo estranho\n"))
        assertNotNull(Engine.friendlyError(null))
    }
}
