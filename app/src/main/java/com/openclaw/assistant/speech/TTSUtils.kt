package com.openclaw.assistant.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * TTSの共通ユーティリティ
 */
object TTSUtils {
    private const val TAG = "TTSUtils"
    const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"

    /**
     * ロケールと高品質な音声のセットアップ
     */
    fun setupVoice(tts: TextToSpeech?, speed: Float) {
        val currentLocale = Locale.getDefault()
        Log.e(TAG, "Current system locale: $currentLocale")

        // エンジン情報をログ出力
        try {
            val engine = tts?.defaultEngine
            Log.e(TAG, "Using TTS Engine: $engine")
        } catch (e: Exception) {
            Log.e(TAG, "Could not get default engine: ${e.message}")
        }

        // システムロケールの設定を試みる
        val result = tts?.setLanguage(currentLocale)
        Log.e(TAG, "setLanguage($currentLocale) result=$result")

        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            // デフォルトが失敗した場合は英語(US)にフォールバック
            val fallbackResult = tts?.setLanguage(Locale.US)
            Log.e(TAG, "Fallback setLanguage(Locale.US) result=$fallbackResult")
        }

        // 高品質な音声（ネットワーク不要のもの優先）を選択
        try {
            val targetLang = tts?.language?.language
            val voices = tts?.voices
            Log.e(TAG, "Available voices count: ${voices?.size ?: 0}")
            
            val bestVoice = voices?.filter { it.locale.language == targetLang }
                ?.firstOrNull { !it.isNetworkConnectionRequired }
                ?: voices?.firstOrNull { it.locale.language == targetLang }

            if (bestVoice != null) {
                tts?.voice = bestVoice
                Log.e(TAG, "Selected voice: ${bestVoice.name} (${bestVoice.locale})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error selecting voice: ${e.message}")
        }

        applyUserConfig(tts, speed)
    }

    /**
     * ユーザー設定の速度を適用する
     */
    fun applyUserConfig(tts: TextToSpeech?, speed: Float) {
        if (tts == null) return
        tts.setSpeechRate(speed)
        tts.setPitch(1.0f)
    }
}
