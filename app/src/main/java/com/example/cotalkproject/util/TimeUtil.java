package com.example.cotalkproject.util;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public final class TimeUtil {

    private TimeUtil() {
    }

    public static String format(long timestamp) {
        if (timestamp <= 0) {
            return "";
        }
        Calendar now = Calendar.getInstance();
        Calendar target = Calendar.getInstance();
        target.setTimeInMillis(timestamp);

        if (sameDay(now, target)) {
            return new SimpleDateFormat("HH:mm", Locale.CHINA)
                    .format(new Date(timestamp));
        }

        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(yesterday, target)) {
            return "昨天 " + new SimpleDateFormat("HH:mm", Locale.CHINA)
                    .format(new Date(timestamp));
        }

        if (now.get(Calendar.YEAR) == target.get(Calendar.YEAR)) {
            return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                    .format(new Date(timestamp));
        }
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
                .format(new Date(timestamp));
    }

    private static boolean sameDay(Calendar left, Calendar right) {
        return left.get(Calendar.YEAR) == right.get(Calendar.YEAR)
                && left.get(Calendar.DAY_OF_YEAR) == right.get(Calendar.DAY_OF_YEAR);
    }
}
