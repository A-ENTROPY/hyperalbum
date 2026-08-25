package com.smartvision.gallery.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Light-weight time formatters that don't allocate SimpleDateFormat on every call.
 */
object DateFormatters {

    private val yearMonth = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy 年 M 月", Locale.getDefault())
    }
    private val fullDate = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }
    private val dayHeader = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy 年 M 月 d 日", Locale.getDefault())
    }

    fun yearMonth(timestampMs: Long): String =
        yearMonth.get()!!.format(Date(timestampMs))

    fun full(timestampMs: Long): String =
        fullDate.get()!!.format(Date(timestampMs))

    fun dayHeader(timestampMs: Long): String =
        dayHeader.get()!!.format(Date(timestampMs))
}