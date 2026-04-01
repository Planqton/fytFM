package org.omri.radio.impl;

import java.util.Calendar;
import java.util.TimeZone;

class DabTime {
    private final int mYear;
    private final int mMonth;
    private final int mDay;
    private final int mHour;
    private final int mMinute;
    private final int mSecond;
    private final int mMilliSeconds;
    private final long mPosixMillis;

    public DabTime(int year, int month, int day, int hour, int minute, int second, int millis) {
        this.mYear = year;
        this.mMonth = month;
        this.mDay = day;
        this.mHour = hour;
        this.mMinute = minute;
        this.mSecond = second;
        this.mMilliSeconds = millis;
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.set(Calendar.YEAR, year);
        calendar.set(Calendar.MONTH, month - 1);
        calendar.set(Calendar.DAY_OF_MONTH, day);
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, second);
        calendar.set(Calendar.MILLISECOND, millis);
        this.mPosixMillis = calendar.getTimeInMillis();
    }

    public long getPosixMillis() {
        return this.mPosixMillis;
    }
}
