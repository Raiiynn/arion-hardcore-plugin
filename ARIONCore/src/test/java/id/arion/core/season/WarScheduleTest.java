package id.arion.core.season;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class WarScheduleTest {
    final ZonedDateTime start = ZonedDateTime.parse("2026-10-14T20:00:00+07:00[Asia/Jakarta]");
    final WarSchedule schedule = new WarSchedule(start, 7, 7, Duration.ofMinutes(60));

    @Test void dayOneIsStartSoFirstWarIsSixDaysLater() {
        assertEquals(start.plusDays(6).toInstant(), schedule.occurrence(0).start());
        assertEquals(start.plusDays(13).toInstant(), schedule.occurrence(1).start());
        assertEquals(start.plusDays(20).toInstant(), schedule.occurrence(2).start());
    }

    @Test void windowIncludesStartAndExcludesEnd() {
        var window = schedule.occurrence(0);
        assertEquals(Duration.ofHours(1), Duration.between(window.start(), window.end()));
        assertTrue(window.contains(window.start()));
        assertFalse(window.contains(window.start().minusNanos(1)));
        assertTrue(window.contains(window.end().minusNanos(1)));
        assertFalse(window.contains(window.end()));
    }

    @Test void invalidScheduleIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> schedule.occurrence(-1));
        assertThrows(IllegalArgumentException.class, () -> new WarSchedule(start, 0, 7, Duration.ofHours(1)));
        assertThrows(IllegalArgumentException.class, () -> new WarSchedule(start, 7, 0, Duration.ofHours(1)));
        assertThrows(IllegalArgumentException.class, () -> new WarSchedule(start, 7, 7, Duration.ZERO));
    }
}
