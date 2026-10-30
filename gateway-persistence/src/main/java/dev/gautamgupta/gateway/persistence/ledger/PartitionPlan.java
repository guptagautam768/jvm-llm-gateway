package dev.gautamgupta.gateway.persistence.ledger;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Daily partition names and which of them retention should drop. */
public final class PartitionPlan {

    public static final Pattern NAME = Pattern.compile("^usage_ledger_p(\\d{8})$");
    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;

    private PartitionPlan() {
    }

    public static String name(LocalDate day) {
        return "usage_ledger_p" + day.format(BASIC);
    }

    public static Optional<LocalDate> dayOf(String relationName) {
        if (relationName == null) {
            return Optional.empty();
        }
        Matcher matcher = NAME.matcher(relationName);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(LocalDate.parse(matcher.group(1), BASIC));
    }

    /** Yesterday through {@code today + precreateDays}, so a request near midnight still has a partition. */
    public static List<LocalDate> ensureDays(LocalDate today, int precreateDays) {
        int ahead = Math.max(0, precreateDays);
        List<LocalDate> days = new ArrayList<>(ahead + 2);
        for (int offset = -1; offset <= ahead; offset++) {
            days.add(today.plusDays(offset));
        }
        return List.copyOf(days);
    }

    /**
     * Drop a partition when its whole day is strictly older than the retention window.
     * A 30-day retention on 30 Oct keeps 30 Sep and drops 29 Sep.
     */
    public static boolean shouldDrop(LocalDate partitionDay, LocalDate today, int retentionDays) {
        int days = Math.max(1, retentionDays);
        LocalDate cutoff = today.minusDays(days);
        return partitionDay.isBefore(cutoff);
    }
}
