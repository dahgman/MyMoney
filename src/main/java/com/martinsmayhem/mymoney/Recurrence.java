package com.martinsmayhem.mymoney;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * How often a scheduled transaction repeats, and the dates that gives.
 *
 * Every interval days, weeks, months or years from the start date; or twice
 * a month (on the start date's day and a second day). Monthly dates are
 * always counted from the start date, so a schedule starting on the 31st
 * falls on the 30th in 30-day months, the 28th or 29th in February, and is
 * back on the 31st after. Day 31 as the second day means the last day.
 *
 * It ends never, on an end date, or after a number of times.
 */
record Recurrence(Unit unit, int interval, int secondDay, LocalDate start, LocalDate endDate, Integer maxCount) {

    enum Unit { DAY, WEEK, MONTH, YEAR, SEMIMONTH }

    /** The usual choices, for the Repeats drop-down. CUSTOM = every N of some unit. */
    enum Preset {
        DAILY("Daily", Unit.DAY, 1),
        WEEKLY("Weekly", Unit.WEEK, 1),
        BIWEEKLY("Every 2 weeks", Unit.WEEK, 2),
        SEMIMONTHLY("Twice a month", Unit.SEMIMONTH, 1),
        MONTHLY("Monthly", Unit.MONTH, 1),
        QUARTERLY("Every 3 months", Unit.MONTH, 3),
        SEMIANNUALLY("Every 6 months", Unit.MONTH, 6),
        YEARLY("Yearly", Unit.YEAR, 1),
        CUSTOM("Every...", null, 0);

        final String label;
        final Unit unit;
        final int interval;

        Preset(String label, Unit unit, int interval) {
            this.label = label;
            this.unit = unit;
            this.interval = interval;
        }

        /** The preset with this unit and interval, or CUSTOM. */
        static Preset of(Unit unit, int interval) {
            for (Preset p : values()) {
                if (p.unit == unit && p.interval == interval) {
                    return p;
                }
            }
            return CUSTOM;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** "Monthly", "Every 2 weeks", "Every 10 days", "Twice a month (1st and 15th)". */
    String describe() {
        Preset p = Preset.of(unit, interval);
        if (unit == Unit.SEMIMONTH) {
            return "Twice a month (" + ordinal(start.getDayOfMonth()) + " and "
                    + (secondDay >= 31 ? "last day" : ordinal(secondDay)) + ")";
        }
        if (p != Preset.CUSTOM) {
            return p.label;
        }
        String name = unit.name().toLowerCase();
        return "Every " + interval + " " + name + (interval == 1 ? "" : "s");
    }

    Recurrence {
        if (interval < 1) {
            throw new IllegalArgumentException("A schedule must repeat at least every 1 " + unit);
        }
    }

    /** Every date the schedule falls on from from to to (both inclusive), in order. */
    List<LocalDate> datesBetween(LocalDate from, LocalDate to) {
        List<LocalDate> list = new ArrayList<>();
        for (int k = 0; maxCount == null || k < maxCount; k++) {
            LocalDate d = nth(k);
            if (d.isAfter(to) || (endDate != null && d.isAfter(endDate))) {
                break;
            }
            if (!d.isBefore(from)) {
                list.add(d);
            }
        }
        return list;
    }

    /** The first date on or after from, or null if the schedule has ended by then. */
    LocalDate nextOnOrAfter(LocalDate from) {
        List<LocalDate> d = datesBetween(from, from.plusYears(5).plusDays(1));
        return d.isEmpty() ? null : d.get(0);
    }

    /** The k-th date (0 = the start), before applying the end. */
    private LocalDate nth(int k) {
        return switch (unit) {
            case DAY -> start.plus((long) k * interval, ChronoUnit.DAYS);
            case WEEK -> start.plus((long) k * interval, ChronoUnit.WEEKS);
            case MONTH -> start.plusMonths((long) k * interval);
            case YEAR -> start.plusYears((long) k * interval);
            case SEMIMONTH -> semimonthly(k);
        };
    }

    /** Twice a month: the k-th of the start day and second day, month by month, from the start. */
    private LocalDate semimonthly(int k) {
        int found = -1;
        for (int month = 0; ; month++) {
            LocalDate first = start.withDayOfMonth(1).plusMonths(month);
            LocalDate a = dayIn(first, start.getDayOfMonth());
            LocalDate b = dayIn(first, secondDay);
            LocalDate early = a.isAfter(b) ? b : a;
            LocalDate late = a.isAfter(b) ? a : b;
            for (LocalDate d : early.equals(late) ? List.of(early) : List.of(early, late)) {
                if (!d.isBefore(start) && ++found == k) {
                    return d;
                }
            }
        }
    }

    /** Day day of that date's month, or the month's last day if it's shorter. */
    private static LocalDate dayIn(LocalDate monthStart, int day) {
        return monthStart.withDayOfMonth(Math.min(day, monthStart.lengthOfMonth()));
    }

    static String ordinal(int n) {
        String suffix = (n % 100 >= 11 && n % 100 <= 13) ? "th"
                : switch (n % 10) {
                    case 1 -> "st";
                    case 2 -> "nd";
                    case 3 -> "rd";
                    default -> "th";
                };
        return n + suffix;
    }
}
