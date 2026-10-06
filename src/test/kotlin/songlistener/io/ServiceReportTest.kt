package songlistener.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServiceReportTest {
    private val log = listOf(
        """{"time":"2026-10-04 10:00:00","type":"app_started"}""",
        """{"time":"2026-10-04 10:01:00","type":"music_started","musicProb":0.8}""",
        """{"time":"2026-10-04 10:01:20","type":"match","live":null,"decision":"streak 1/2","confident":true,"margin":0.2,"top":[{"song":"Hark <b> (#12)","key":"12|Hark <b>","score":0.86,"transpose":0,"tempo":1.0,"section":-1}]}""",
        """{"time":"2026-10-04 10:01:22","type":"match","live":null,"decision":"suggested","confident":true,"margin":0.2,"top":[{"song":"Hark <b> (#12)","key":"12|Hark <b>","score":0.88,"transpose":0,"tempo":1.0,"section":-1}]}""",
        """{"time":"2026-10-04 10:01:40","type":"song_live","song":"Hark <b> (#12)","key":"12|Hark <b>","section":0}""",
        """{"time":"2026-10-04 10:01:40","type":"take_started","song":"Hark <b> (#12)","key":"12|Hark <b>","section":0,"introSeconds":40.0}""",
        """{"time":"2026-10-04 10:02:00","type":"match","live":"12|Hark <b>","liveSection":0,"decision":"accuracy_hit","confident":true,"margin":0.1,"top":[{"song":"Hark <b> (#12)","key":"12|Hark <b>","score":0.84,"transpose":0,"tempo":1.0,"section":0},{"song":"Other","key":"3|Other","score":0.74,"transpose":2,"tempo":1.0}]}""",
        """{"time":"2026-10-04 10:02:02","type":"match","live":"12|Hark <b>","liveSection":1,"decision":"accuracy_miss","confident":false,"margin":0.01,"top":[{"song":"Other","key":"3|Other","score":0.79,"transpose":2,"tempo":1.0},{"song":"Hark <b> (#12)","key":"12|Hark <b>","score":0.78,"transpose":0,"tempo":1.0,"section":0}]}""",
        """{"time":"2026-10-04 10:02:30","type":"music_stopped","musicProb":0.45}""",
        """{"time":"2026-10-04 10:02:31","type":"music_started","musicProb":0.8}""",
        """{"time":"2026-10-04 10:04:40","type":"take_saved","song":"Hark <b> (#12)","key":"12|Hark <b>","section":3,"introSeconds":40.0,"songSeconds":178.0,"takesKept":2}""",
        """{"time":"2026-10-04 10:04:40","type":"song_off_screen"}""",
        """{"time":"2026-10-04 10:04:41","type":"music_stopped","musicProb":0.1}""",
        """{"time":"2026-10-04 10:04:45","type":"music_break","queryFramesCleared":24}""",
        """not json at all""",
    )

    @Test
    fun `songs on screen and music spans are rebuilt from the log`() {
        val evs = ServiceReport.parse(log)
        assertEquals(14, evs.size) // the bad line is skipped
        val songs = ServiceReport.songsOnScreen(evs)
        assertEquals(1, songs.size)
        assertEquals("12|Hark <b>", songs[0].key)
        assertEquals(180, songs[0].span.seconds)
        val music = ServiceReport.musicSpans(evs)
        assertEquals(listOf(90L, 130L), music.map { it.seconds })
    }

    @Test
    fun `the page has the tables and escapes song names`() {
        val html = ServiceReport.render("2026-10-04", log)
        // Shown number first, from the key, and escaped.
        assertTrue("#12 Hark &lt;b&gt;" in html)
        assertFalse("<b>" in html.substringAfter("<main>").substringBefore("Music detection").replace("<b>1</b>", ""))
        assertTrue("saved · 2m 58s + 40s intro" in html)
        assertTrue("1 / 1" in html, "hits / misses")
        assertTrue("1 / 1</td><td class=n>0.84" in html, "section right 1 of 1, best right-song 0.84")
        assertTrue("0.79" in html, "best wrong-song score")
        assertTrue("✓ #12 Hark &lt;b&gt;" in html, "the suggestion came true")
        assertTrue("<b>1</b><span>short dips" in html)
        assertTrue("<b>1</b><span>real breaks" in html)
    }

    @Test
    fun `an empty day says so`() {
        assertTrue("No songs and no music" in ServiceReport.render("2026-10-05", listOf("""{"time":"2026-10-05 09:00:00","type":"app_started"}""")))
    }

    @Test
    fun `late slides and wrong-slide warnings get their own sections`() {
        val html = ServiceReport.render("2026-10-04", listOf(
            """{"time":"2026-10-04 10:00:00","type":"app_started"}""",
            """{"time":"2026-10-04 10:00:10","type":"music_started","musicProb":0.8}""",
            """{"time":"2026-10-04 10:00:10","type":"lookback_started"}""",
            """{"time":"2026-10-04 10:01:40","type":"song_live","song":"x","key":"5|Кистью Творца","section":0}""",
            """{"time":"2026-10-04 10:01:40","type":"late_slide","song":"x","key":"5|Кистью Творца","lateSeconds":90.0,"hintLeadSeconds":40.0,"caught":true}""",
            """{"time":"2026-10-04 10:03:00","type":"wrong_slide_warning","song":"x","key":"5|Кистью Творца","shownScore":0.55,"heard":"y","heardKey":"93|Великий Бог","heardScore":0.71}""",
            """{"time":"2026-10-04 10:03:05","type":"show_requested","detail":"93|Великий Бог songbook=PV 2001"}""",
            """{"time":"2026-10-04 10:03:09","type":"show_result","detail":"93|Великий Бог shown"}""",
            """{"time":"2026-10-04 10:03:09","type":"song_live","song":"y","key":"93|Великий Бог","section":0}""",
        ))
        assertTrue("<h2>Late slides</h2>" in html)
        assertTrue("#5 Кистью Творца" in html)
        assertTrue("1m 30s" in html, "how late")
        assertTrue("40s before" in html, "the early guess's lead")
        assertTrue("Named before the slide: 1 of 1 that could have been" in html)
        assertTrue("<h2>Wrong slides</h2>" in html)
        assertTrue("#93 Великий Бог" in html)
        assertTrue("0.55 vs 0.71" in html)
        assertTrue("Show it clicked after 5s — shown" in html, html.substringAfter("<h2>Wrong slides</h2>").take(800))
    }

    @Test
    fun `an older log without late_slide lines still shows its late slides`() {
        val evs = ServiceReport.parse(listOf(
            """{"time":"2026-09-27 19:22:00","type":"song_off_screen"}""",
            """{"time":"2026-09-27 19:22:40","type":"music_started","musicProb":0.8}""",
            """{"time":"2026-09-27 19:24:12","type":"song_live","song":"x","key":"0|Кистью Творца созданы Небеса","section":0}""",
            """{"time":"2026-09-27 19:27:00","type":"song_off_screen"}""",
        ))
        val late = ServiceReport.lateSlides(evs, ServiceReport.songsOnScreen(evs)).single()
        assertEquals(92.0, late.lateSeconds)
        assertEquals(null, late.hintLead)
    }

    @Test
    fun `a late slide after other music, or of an unlearned song, isn't counted as missed`() {
        val html = ServiceReport.render("2026-10-11", listOf(
            """{"time":"2026-10-11 10:00:00","type":"app_started"}""",
            """{"time":"2026-10-11 10:01:00","type":"music_started","musicProb":0.8}""",
            """{"time":"2026-10-11 10:05:00","type":"song_live","song":"x","key":"426|Холм","section":0}""",
            """{"time":"2026-10-11 10:05:00","type":"late_slide","song":"x","key":"426|Холм","lateSeconds":240.0,"caught":false,"heardBefore":false}""",
            """{"time":"2026-10-11 10:09:00","type":"song_off_screen"}""",
            """{"time":"2026-10-11 10:10:00","type":"music_started","musicProb":0.8}""",
            """{"time":"2026-10-11 10:14:00","type":"song_live","song":"y","key":"2616|Новая","section":0}""",
            """{"time":"2026-10-11 10:14:00","type":"late_slide","song":"y","key":"2616|Новая","lateSeconds":240.0,"caught":false}""",
        ))
        assertTrue("other music played first" in html)
        assertTrue("not learned yet" in html)
        assertTrue("missed" !in html.substringAfter("<h2>Late slides</h2>").substringBefore("</table>"))
        assertTrue("Named before the slide: 0 of 0 that could have been (2 late in all" in html)
    }
}
