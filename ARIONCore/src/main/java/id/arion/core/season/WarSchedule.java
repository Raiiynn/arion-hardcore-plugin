package id.arion.core.season;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;

/** Schedule calculation only; Milestone 1 does not start or award wars. */
public record WarSchedule(ZonedDateTime seasonStart, int firstDay, int intervalDays, Duration duration) {
    public WarSchedule {
        if (seasonStart == null || firstDay < 1 || intervalDays < 1 || duration == null
                || duration.isNegative() || duration.isZero()
                || duration.compareTo(Duration.ofDays(intervalDays)) >= 0) {
            throw new IllegalArgumentException("Invalid war schedule");
        }
    }

    public Window occurrence(long index) {
        if (index < 0) throw new IllegalArgumentException("War index must be non-negative");
        long days = Math.addExact(firstDay - 1L, Math.multiplyExact(index, intervalDays));
        var start = seasonStart.plusDays(days).toInstant();
        return new Window(start, start.plus(duration));
    }

    public record Window(Instant start, Instant end) {
        public boolean contains(Instant instant) { return !instant.isBefore(start) && instant.isBefore(end); }
    }
}
