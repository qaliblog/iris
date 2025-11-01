package com.qali.iris

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

/**
 * Centralized logcat manager that can be accessed from multiple fragments
 */
object LogcatManager {
    
    private const val PREFS_NAME = "logcat_prefs"
    private const val MAX_LOG_LINES = 200
    private val logBuffer = Collections.synchronizedList(mutableListOf<String>())
    private val listeners = mutableListOf<(String) -> Unit>()
    
    fun addLog(message: String, tag: String = "iris") {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logLine = "[$timestamp] [$tag] $message"
        
        synchronized(logBuffer) {
            logBuffer.add(logLine)
            
            // Keep only last maxLogLines
            if (logBuffer.size > MAX_LOG_LINES) {
                logBuffer.removeAt(0)
            }
        }
        
        // Notify listeners (with error handling to prevent crashes)
        val fullLog = try {
            getLogText()
        } catch (e: Exception) {
            Log.e("LogcatManager", "Error getting log text for listeners: ${e.message}", e)
            return
        }
        
        synchronized(listeners) {
            val listenersCopy = listeners.toList() // Create copy to avoid modification during iteration
            listenersCopy.forEach { listener ->
                try {
                    listener(fullLog)
                } catch (e: Exception) {
                    Log.e("LogcatManager", "Error in logcat listener: ${e.message}", e)
                }
            }
        }
        
        // Also log to system logcat
        Log.d(tag, message)
    }
    
    fun getLogText(): String {
        return try {
            synchronized(logBuffer) {
                if (logBuffer.isEmpty()) {
                    "Waiting for logs..."
                } else {
                    logBuffer.joinToString("\n")
                }
            }
        } catch (e: Exception) {
            Log.e("LogcatManager", "Error getting log text: ${e.message}", e)
            "Error reading logs: ${e.message}"
        }
    }
    
    fun registerListener(listener: (String) -> Unit) {
        synchronized(listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
        }
        // Immediately send current log (with error handling)
        try {
            val logText = getLogText()
            listener(logText)
        } catch (e: Exception) {
            Log.e("LogcatManager", "Error in listener during registration: ${e.message}", e)
        }
    }
    
    fun unregisterListener(listener: (String) -> Unit) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }
    
    fun clear() {
        try {
            synchronized(logBuffer) {
                logBuffer.clear()
            }
            synchronized(listeners) {
                listeners.forEach { listener ->
                    try {
                        listener("")
                    } catch (e: Exception) {
                        Log.e("LogcatManager", "Error clearing logs in listener: ${e.message}", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("LogcatManager", "Error clearing logs: ${e.message}", e)
        }
    }
}
