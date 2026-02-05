package com.openclaw.assistant.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * TTSの共通ユーティリティ
 */
object TTSUtils {
    private const val TAG = "TTSUtils"

    /**
     * ロケールと高品質な音声のセットアップ
     */
    fun setupVoice(tts: TextToSpeech?) {
        val currentLocale = Locale.getDefault()
        Log.e(TAG, "Current system locale: $currentLocale")

        // システムロケールの設定を試みる
        val result = tts?.setLanguage(currentLocale)
        Log.e(TAG, "setLanguage result=$result")

        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            // デフォルトが失敗した場合は英語(US)にフォールバック
            val fallbackResult = tts?.setLanguage(Locale.US)
            Log.e(TAG, "Fallback setLanguage(Locale.US) result=$fallbackResult")
        }

        // 高品質な音声（ネットワーク不要のもの優先）を選択
        try {
            val targetLang = tts?.language?.language
            val voices = tts?.voices
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

        // 言語に応じた速度調整
        val rate = if (tts?.language?.language == "ja") 1.5f else 1.2f
        tts?.setSpeechRate(rate)
        tts?.setPitch(1.0f)
    }

    /**
     * テキスト内容に応じてTTSの言語や読み上げ速度を簡易的に切り替える。
     *
     * - 「日本語」と判定する条件は、ひらがな・カタカナ・漢字が含まれている場合。
     * - アルファベットが含まれている場合は「英語（またはラテン文字ベース）」とみなす。
     * - いずれにも該当しない場合はシステムデフォルトを使用する。
     */
    fun applyLanguageForText(tts: TextToSpeech?, text: String) {
        if (text.isEmpty()) return

        // 効率のため最初の100文字程度で判定
        val sample = text.take(100)

        // 漢字、ひらがな、カタカナのチェック
        val hasJapanese = sample.any {
            it in '\u3040'..'\u309F' || // Hiragana
            it in '\u30A0'..'\u30FF' || // Katakana
            it in '\u4E00'..'\u9FAF'    // Kanji
        }

        if (hasJapanese) {
            tts?.language = Locale.JAPANESE
            tts?.setSpeechRate(1.5f)
        } else {
            val hasLatin = sample.any { it in 'a'..'z' || it in 'A'..'Z' }
            if (hasLatin) {
                tts?.language = Locale.US
                tts?.setSpeechRate(1.2f)
            } else {
                // 日本語も英語も含まれない場合はシステムデフォルトに戻す
                val defaultLocale = Locale.getDefault()
                tts?.language = defaultLocale
                tts?.setSpeechRate(1.0f)
            }
        }
    }
}
