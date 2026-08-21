package com.shunfeng.ledger

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import org.json.JSONArray
import java.util.Calendar
import java.util.Locale

/**
 * 桌面小组件 Provider（方案 B：数据看板 + 今日订单列表）
 *
 * 数据来源：SharedPreferences "widget_data" 中键 "orders_json" 存有完整订单 JSON 数组。
 * 由 MainActivity 的 JSInterface.syncOrders() 写入，每次订单增删改后实时同步。
 */
class TodayWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val TAG = "TodayWidget"
        private const val PREFS_NAME = "widget_data"
        private const val KEY_ORDERS = "orders_json"

        /** 通知所有桌面小组件刷新（由 MainActivity 调用） */
        fun notifyWidgetChanged(context: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(context)
                val cn = ComponentName(context, TodayWidgetProvider::class.java)
                val ids = mgr.getAppWidgetIds(cn)
                if (ids.isNotEmpty()) {
                    val intent = Intent(context, TodayWidgetProvider::class.java)
                        .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    context.sendBroadcast(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "notifyWidgetChanged error", e)
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            updateWidget(context, appWidgetManager, id)
        }
    }

    /** 填充并更新单个 Widget */
    private fun updateWidget(context: Context, mgr: AppWidgetManager, widgetId: Int) {
        try {
            val rv = RemoteViews(context.packageName, R.layout.widget_today)

            // 读取数据
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val ordersJson = prefs.getString(KEY_ORDERS, null)

            // 解析订单
            val todayOrders = parseTodayOrders(ordersJson)

            // 填充数据
            fillWidgetData(rv, todayOrders)

            // 整体点击 → 打开 App（使用 PendingIntent，安全）
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                context, widgetId, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.widgetRoot, pi)

            mgr.updateAppWidget(widgetId, rv)
        } catch (e: Exception) {
            Log.e(TAG, "updateWidget error", e)
        }
    }

    /** 从 JSON 数组中解析今日订单
     *  - 只保留今日订单
     *  - 按 time 字段升序排序（早→晚）
     *  - 返回全部当日订单（不截断），订单总数与列表展示分别处理 */
    private fun parseTodayOrders(jsonStr: String?): List<OrderInfo> {
        if (jsonStr.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(jsonStr)
            val today = todayDateStr()
            val list = mutableListOf<OrderInfo>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val date = obj.optString("date", "")
                if (date != today) continue
                val platform = obj.optString("platform", "gaode")
                val amount = obj.optDouble("amount", 0.0)
                val pickup = obj.optString("pickup", "")
                val dropoff = obj.optString("dropoff", "")
                val time = obj.optString("time", "")
                val createdAt = obj.optLong("createdAt", 0L)
                list.add(OrderInfo(platform, amount, pickup, dropoff, time, createdAt))
            }
            // 按时间排序：有 time 字段按 time 升序（早→晚）；没有则按 createdAt 升序
            list.sortWith(compareBy<OrderInfo> { it.time }.thenBy { it.createdAt })
            list
        } catch (e: Exception) {
            Log.e(TAG, "parseOrders error", e)
            emptyList()
        }
    }

    private fun truncatePickup(s: String): String {
        // 起点：直接显示，不做手动截断，由 ellipsize 自然省略
        return s.trim().ifEmpty { "起点" }
    }

    private fun truncateDest(s: String): String {
        // 终点：直接显示，不做手动截断，由 ellipsize 自然省略
        return s.trim().ifEmpty { "终点" }
    }

    private fun formatAmount(amount: Double): String {
        // 金额完整显示：¥整数 或 ¥整数.小数（.00省略）
        val intPart = amount.toInt()
        val decPart = ((amount - intPart) * 100 + 0.5).toInt()
        return if (decPart == 0) {
            "¥$intPart"
        } else {
            "¥${intPart}.${String.format(Locale.getDefault(), "%02d", decPart)}"
        }
    }

    private fun todayDateStr(): String {
        val cal = Calendar.getInstance()
        return String.format(Locale.getDefault(), "%04d-%02d-%02d",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
    }

    /** 将数据填充到 RemoteViews */
    private fun fillWidgetData(rv: RemoteViews, orders: List<OrderInfo>) {
        // 日期
        val cal = Calendar.getInstance()
        val weekdays = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        val dateStr = String.format(Locale.getDefault(), "%d月%d日 %s",
            cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
            weekdays[cal.get(Calendar.DAY_OF_WEEK) - 1])
        rv.setTextViewText(R.id.widgetDate, dateStr)

        // 今日收入汇总（整数/小数拆分显示，放大填充左侧）
        val totalAmount = orders.sumOf { it.amount }
        val amountStr = String.format(Locale.getDefault(), "%.2f", totalAmount)
        val parts = amountStr.split(".")
        rv.setTextViewText(R.id.widgetAmountInt, parts[0])
        rv.setTextViewText(R.id.widgetAmountDec, "." + parts.getOrElse(1) { "00" })

        // 订单数（当日全部订单数，与实际一致）与客单价
        val count = orders.size
        val avg = if (count > 0) totalAmount / count else 0.0
        rv.setTextViewText(R.id.widgetCount, "${count} 单")
        rv.setTextViewText(R.id.widgetAvg, "客单价 ¥${String.format(Locale.getDefault(), "%.0f", avg)}")

        // 订单列表（最多3条，取当日最晚的3条但保持时间升序；起点终点分两行，金额完整显示）
        val displayOrders = if (orders.size > 3) orders.subList(orders.size - 3, orders.size) else orders
        if (displayOrders.isEmpty()) {
            rv.setViewVisibility(R.id.widgetEmpty, View.VISIBLE)
            rv.setViewVisibility(R.id.orderRow1, View.GONE)
            rv.setViewVisibility(R.id.orderRow2, View.GONE)
            rv.setViewVisibility(R.id.orderRow3, View.GONE)
        } else {
            rv.setViewVisibility(R.id.widgetEmpty, View.GONE)

            val maxRows = minOf(displayOrders.size, 3)
            for (i in 0 until 3) {
                val rowId = when (i) { 0 -> R.id.orderRow1; 1 -> R.id.orderRow2; else -> R.id.orderRow3 }
                val dotId = when (i) { 0 -> R.id.dot1; 1 -> R.id.dot2; else -> R.id.dot3 }
                val routeId = when (i) { 0 -> R.id.route1; 1 -> R.id.route2; else -> R.id.route3 }
                val destId = when (i) { 0 -> R.id.dest1; 1 -> R.id.dest2; else -> R.id.dest3 }
                val amtId = when (i) { 0 -> R.id.amt1; 1 -> R.id.amt2; else -> R.id.amt3 }

                if (i < maxRows) {
                    val order = displayOrders[i]
                    rv.setViewVisibility(rowId, View.VISIBLE)

                    // 起点（第一行，深色）
                    val pickup = order.pickup.ifBlank { "起点" }
                    rv.setTextViewText(routeId, truncatePickup(pickup))

                    // 终点（第二行，浅色，带→前缀）
                    val dropoff = order.dropoff.ifBlank { "终点" }
                    rv.setTextViewText(destId, truncateDest(dropoff))

                    // 金额（完整显示，不截断）
                    rv.setTextViewText(amtId, formatAmount(order.amount))

                    // 平台色点
                    val dotRes = when (order.platform) {
                        "gaode" -> R.drawable.widget_dot_gaode
                        "didi" -> R.drawable.widget_dot_didi
                        else -> R.drawable.widget_dot_grey
                    }
                    rv.setImageViewResource(dotId, dotRes)
                } else {
                    rv.setViewVisibility(rowId, View.GONE)
                }
            }
        }
    }

    private data class OrderInfo(
        val platform: String,
        val amount: Double,
        val pickup: String,
        val dropoff: String,
        val time: String,
        val createdAt: Long
    )
}
