package edu.upt.assistant.data.metrics

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File

data class GenerationMetrics(
    val timestamp: Long,
    val prefillTimeMs: Long,
    val firstTokenDelayMs: Long,
    val decodeSpeed: Double,
    val batteryDelta: Float,
    val startTempC: Float,
    val endTempC: Float,
    val promptChars: Int,
    val promptTokens: Int,
    val nThreads: Int,
    val nBatch: Int,
    val nUbatch: Int,
    val modelQuant: String
) {
    fun toCsvRow(): String = listOf(
        timestamp,
        prefillTimeMs,
        firstTokenDelayMs,
        decodeSpeed,
        batteryDelta,
        startTempC,
        endTempC,
        promptChars,
        promptTokens,
        nThreads,
        nBatch,
        nUbatch,
        modelQuant
    ).joinToString(",", postfix = "\n")
}

object MetricsLogger {
    private const val FILE_NAME = "generation_metrics.csv"
    private const val HEADER = "timestamp,prefill_ms,first_token_ms,decode_speed,battery_delta,temp_start,temp_end,prompt_chars,prompt_tokens,n_threads,n_batch,n_ubatch,model\n"

    fun getFile(context: Context): File = File(context.filesDir, FILE_NAME)

    fun log(context: Context, metrics: GenerationMetrics) {
        val file = getFile(context)
        val isNew = !file.exists()
        if (isNew) {
            file.writeText(HEADER)
        }
        file.appendText(metrics.toCsvRow())
    }

    fun batteryLevel(context: Context): Float {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toFloat()
    }

    fun deviceTemperature(context: Context): Float {
        return try {
            val thermal = context.getSystemService("thermalservice")
            val tmClass = Class.forName("android.os.ThermalManager")
            val tempClass = Class.forName("android.os.Temperature")
            val typeField = tempClass.getField("TYPE_SKIN")
            val type = typeField.getInt(null)
            val method = tmClass.getMethod("getCurrentTemperature", Int::class.javaPrimitiveType)
            val tempObj = method.invoke(thermal, type)
            val valueMethod = tempClass.getMethod("getValue")
            (valueMethod.invoke(tempObj) as? Float) ?: fallbackBatteryTemp(context)
        } catch (e: Exception) {
            fallbackBatteryTemp(context)
        }
    }

    private fun fallbackBatteryTemp(context: Context): Float {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        return temp / 10f
    }
}

