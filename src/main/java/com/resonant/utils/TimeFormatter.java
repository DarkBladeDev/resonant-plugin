package com.resonant.utils;

public final class TimeFormatter {

    private TimeFormatter() {
    }

    public static String formatSeconds(long seconds) {
        if (seconds <= 0) {
            return "permanente";
        }
        long days = seconds / 86400;
        seconds %= 86400;
        long hours = seconds / 3600;
        seconds %= 3600;
        long minutes = seconds / 60;
        seconds %= 60;
        StringBuilder builder = new StringBuilder();
        append(builder, days, "d");
        append(builder, hours, "h");
        append(builder, minutes, "m");
        append(builder, seconds, "s");
        if (builder.length() == 0) {
            return "0s";
        }
        return builder.toString().trim();
    }

    private static void append(StringBuilder builder, long value, String suffix) {
        if (value <= 0) {
            return;
        }
        builder.append(value).append(suffix).append(' ');
    }
}
