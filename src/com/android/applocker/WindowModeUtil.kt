package com.android.applocker

import android.app.ActivityManager
import android.text.TextUtils
import android.util.Log
import java.lang.reflect.Method

object WindowModeUtil {
    private const val PKG_APP_LOCKER = "com.android.applocker"
    private const val TAG = "WindowModeUtil"

    @JvmStatic
    fun isAppInMultiWindowMode(): Boolean {
        return isAppInWindowMode("inMultiWindowMode")
    }

    @JvmStatic
    fun isAppInWindowformWindowMode(): Boolean {
        return isAppInWindowMode("isNtWindowformWindowMode")
    }

    @JvmStatic
    fun isAppInWindowMode(methodName: String): Boolean {
        val tasks = getTask(3)
        if (!tasks.isNullOrEmpty()) {
            for ((index, task) in tasks.withIndex()) {
                try {
                    val windowingMode = getWindowingMode(task)
                    val packageName = task.topActivity?.packageName
                    Log.d(TAG, "isAppInWindowMode $methodName$index - $packageName windowingMode $windowingMode")
                    if (TextUtils.equals(packageName, PKG_APP_LOCKER) && checkMode(methodName, windowingMode)) {
                        return true
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "isAppInWindowMode Exception: ${e}")
                }
            }
        }
        return false
    }

    @JvmStatic
    fun getTask(maxNum: Int): List<ActivityManager.RunningTaskInfo>? {
        return try {
            val cls = Class.forName("android.app.ActivityTaskManager")
            val getInstanceMethod: Method = cls.getDeclaredMethod("getInstance").apply { isAccessible = true }
            val manager = getInstanceMethod.invoke(null)
            val getTasksMethod: Method = cls.getDeclaredMethod("getTasks", Int::class.javaPrimitiveType).apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            getTasksMethod.invoke(manager, maxNum) as? List<ActivityManager.RunningTaskInfo>
        } catch (e: Exception) {
            Log.d(TAG, "getTask Exception: ${e}")
            null
        }
    }

    @JvmStatic
    fun getWindowingMode(taskInfo: ActivityManager.RunningTaskInfo): Int {
        return try {
            val method = Class.forName("android.app.TaskInfo")
                .getDeclaredMethod("getWindowingMode")
                .apply { isAccessible = true }
            (method.invoke(taskInfo) as? Int) ?: 0
        } catch (e: Exception) {
            Log.d(TAG, "getWindowingMode Exception: ${e}")
            0
        }
    }

    @JvmStatic
    fun checkMode(methodName: String, mode: Int): Boolean {
        return try {
            val method = Class.forName("android.app.WindowConfiguration")
                .getDeclaredMethod(methodName, Int::class.javaPrimitiveType)
            val result = method.invoke(null, mode) as? Boolean ?: false
            Log.d(TAG, "checkMode() = [$result]")
            result
        } catch (e: Exception) {
            Log.d(TAG, "checkMode method $methodName Exception: ${e}")
            false
        }
    }
}
