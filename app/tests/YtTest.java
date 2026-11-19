package com.imgmine.launcher67;
import static org.junit.Assert.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class YtTest {

    @Test public void videoIdVariants() {
        assertEquals("dQw4w9WgXcQ", Yt.videoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertEquals("dQw4w9WgXcQ", Yt.videoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s"));
        assertEquals("dQw4w9WgXcQ", Yt.videoId("https://youtu.be/dQw4w9WgXcQ"));
        assertEquals("dQw4w9WgXcQ", Yt.videoId("https://www.youtube.com/live/dQw4w9WgXcQ?feature=share"));
        assertEquals("dQw4w9WgXcQ", Yt.videoId("https://www.youtube.com/shorts/dQw4w9WgXcQ"));
        assertEquals("dQw4w9WgXcQ", Yt.videoId("  dQw4w9WgXcQ  "));
        assertNull(Yt.videoId("https://example.com"));
        assertNull(Yt.videoId("short"));
    }

    @Test public void parseChatIdFixtures() {
        assertEquals("Cg0KC2RRdzR3OVdnWGNR", Yt.parseChatId(
                "{\"items\":[{\"liveStreamingDetails\":{\"activeLiveChatId\":\"Cg0KC2RRdzR3OVdnWGNR\"}}]}"));
        assertNull(Yt.parseChatId("{\"items\":[]}"));
        assertNull(Yt.parseChatId("{\"items\":[{}]}"));           // not live
        assertNull(Yt.parseChatId("not json"));
    }

    @Test public void parsePollFiltersAndClamps() {
        String json = "{\"nextPageToken\":\"T2\",\"pollingIntervalMillis\":500,\"items\":["
                + "{\"snippet\":{\"type\":\"textMessageEvent\",\"displayMessage\":\"hello :) \\\"gg\\\"\",\"offlineAt\":null},"
                + "\"authorDetails\":{\"displayName\":\"viewer one\"}},"
                + "{\"snippet\":{\"type\":\"textMessageEvent\",\"displayMessage\":\"retracted\",\"offlineAt\":\"2026-11-19T10:00:00Z\"},"
                + "\"authorDetails\":{\"displayName\":\"ghost\"}},"
                + "{\"snippet\":{\"type\":\"superChatEvent\",\"displayMessage\":\"paid\",\"offlineAt\":null},"
                + "\"authorDetails\":{\"displayName\":\"whale\"}}]}";
        Yt.PollResult r = Yt.parsePoll(json);
        assertNull(r.error);
        assertEquals("T2", r.nextToken);
        assertEquals(5000, r.pollMs);                       // clamped up to 5s, quota-friendly
        assertEquals(1, r.msgs.size());
        assertEquals("viewer one", r.msgs.get(0).author);
        assertEquals("hello :) \"gg\"", r.msgs.get(0).text);
    }

    @Test public void parsePollErrorAndBigInterval() {
        Yt.PollResult err = Yt.parsePoll("{\"error\":{\"message\":\"quotaExceeded\"}}");
        assertEquals("quotaExceeded", err.error);
        Yt.PollResult slow = Yt.parsePoll("{\"pollingIntervalMillis\":999999,\"items\":[]}");
        assertEquals(60000, slow.pollMs);                   // clamped down to 60s
        Yt.PollResult bad = Yt.parsePoll("{broken");
        assertNotNull(bad.error);
    }
}
