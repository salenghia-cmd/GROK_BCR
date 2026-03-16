/*
 * SPDX-FileCopyrightText: 2022-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.chiller3.bcr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.UserManager
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import com.chiller3.bcr.extension.DOCUMENTSUI_AUTHORITY
import com.chiller3.bcr.extension.safTreeToDocument
import com.chiller3.bcr.format.Format
import com.chiller3.bcr.output.Retention
import com.chiller3.bcr.rule.RecordRule
import com.chiller3.bcr.settings.SettingsActivity
import com.chiller3.bcr.template.Template
import kotlinx.serialization.json.Json
import java.io.File

class Preferences(initialContext: Context) {

    companion object {
        private val TAG = Preferences::class.java.simpleName

        // ── Keys gốc ────────────────────────────────────────────────────────────────
        const val PREF_WEBSOCKET_URL = "websocket_url"
        const val PREF_CALL_RECORDING = "call_recording"
        const val PREF_RECORD_RULES = "record_rules"
        const val PREF_OUTPUT_DIR = "output_dir"
        const val PREF_FILENAME_TEMPLATE = "filename_template"
        const val PREF_OUTPUT_FORMAT = "output_format"
        const val PREF_MIN_DURATION = "min_duration"
        private const val PREF_WRITE_METADATA = "write_metadata"
        private const val PREF_RECORD_TELECOM_APPS = "record_telecom_apps"
        private const val PREF_RECORD_DIALING_STATE = "record_dialing_state"
        private const val PREF_NOTIFICATION_OPEN_DIR = "notification_open_dir"
        const val PREF_SHOW_LAUNCHER_ICON = "show_launcher_icon"
        const val PREF_VERSION = "version"
        private const val PREF_FORCE_DIRECT_BOOT = "force_direct_boot"
        const val PREF_MIGRATE_DIRECT_BOOT = "migrate_direct_boot"
        const val PREF_SAVE_LOGS = "save_logs"
        const val PREF_ADD_NEW_RULE = "add_new_rule"

        // Not associated with a UI preference
        private const val PREF_DEBUG_MODE = "debug_mode"
        private const val PREF_FORMAT_NAME = "codec_name"
        private const val PREF_FORMAT_PARAM_PREFIX = "codec_param_"
        private const val PREF_FORMAT_SAMPLE_RATE_PREFIX = "codec_sample_rate_"
        private const val PREF_FORMAT_STEREO = "stereo"
        const val PREF_OUTPUT_RETENTION = "output_retention"
        private const val PREF_NEXT_NOTIFICATION_ID = "next_notification_id"

        // ── Keys mới cho realtime bridge ───────────────────────────────────────────
        const val PREF_ENABLE_REALTIME_BRIDGE = "enable_realtime_bridge"
        const val PREF_REALTIME_OUT_URL = "realtime_out_url"
        const val PREF_CONTROL_URL = "control_url"
        const val PREF_DEVICE_ID = "device_id"
        const val PREF_AUTH_TOKEN = "auth_token"
        const val PREF_SESSION_MODE = "session_mode"
        const val PREF_AUTO_ATTACH_WEB = "auto_attach_web"
        const val PREF_PLAYBACK_MODE = "playback_mode"
        const val PREF_INJECT_MODE = "inject_mode"

        const val CATEGORY_DEBUG = "debug"

        // Defaults
        val DEFAULT_FILENAME_TEMPLATE = Template(
            "{date}" +
                    "[_{direction}|]" +
                    "[_sim{sim_slot}|]" +
                    "[_{phone_number}|]" +
                    "[_[{contact_name}|{caller_name}|{call_log_name}]|]"
        )

        val DEFAULT_RECORD_RULES = listOf(
            RecordRule(
                callNumber = RecordRule.CallNumber.Any,
                callType = RecordRule.CallType.ANY,
                simSlot = RecordRule.SimSlot.Any,
                action = RecordRule.Action.SAVE,
            ),
        )

        private val JSON_FORMAT = Json { ignoreUnknownKeys = true }

        fun isFormatKey(key: String): Boolean =
            key == PREF_FORMAT_NAME ||
                    key.startsWith(PREF_FORMAT_PARAM_PREFIX) ||
                    key.startsWith(PREF_FORMAT_SAMPLE_RATE_PREFIX) ||
                    key == PREF_FORMAT_STEREO
    }

    private val context = if (initialContext.isDeviceProtectedStorage) {
        initialContext
    } else {
        initialContext.createDeviceProtectedStorageContext()
    }

    private val userManager = context.getSystemService(UserManager::class.java)

    internal val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    // ── Properties mới cho realtime bridge ──────────────────────────────────────

    var enableRealtimeBridge: Boolean
        get() = prefs.getBoolean(PREF_ENABLE_REALTIME_BRIDGE, false)
        set(value) = prefs.edit().putBoolean(PREF_ENABLE_REALTIME_BRIDGE, value).apply()

    var realtimeOutUrl: String?
        get() = prefs.getString(PREF_REALTIME_OUT_URL, null)?.trim()?.ifBlank { null }
        set(value) = prefs.edit {
            if (value.isNullOrBlank()) {
                remove(PREF_REALTIME_OUT_URL)
            } else {
                putString(PREF_REALTIME_OUT_URL, value.trim())
            }
        }

    var controlUrl: String?
        get() = prefs.getString(PREF_CONTROL_URL, null)?.trim()?.ifBlank { null }
        set(value) = prefs.edit {
            if (value.isNullOrBlank()) {
                remove(PREF_CONTROL_URL)
            } else {
                putString(PREF_CONTROL_URL, value.trim())
            }
        }

    var deviceId: String?
        get() = prefs.getString(PREF_DEVICE_ID, "a51-root")?.trim()?.ifBlank { null }
        set(value) = prefs.edit {
            if (value.isNullOrBlank()) {
                remove(PREF_DEVICE_ID)
            } else {
                putString(PREF_DEVICE_ID, value.trim())
            }
        }

    var authToken: String?
        get() = prefs.getString(PREF_AUTH_TOKEN, null)?.trim()?.ifBlank { null }
        set(value) = prefs.edit {
            if (value.isNullOrBlank()) {
                remove(PREF_AUTH_TOKEN)
            } else {
                putString(PREF_AUTH_TOKEN, value.trim())
            }
        }

    var sessionMode: String
        get() = prefs.getString(PREF_SESSION_MODE, "default") ?: "default"
        set(value) = prefs.edit { putString(PREF_SESSION_MODE, value) }

    var autoAttachWeb: Boolean
        get() = prefs.getBoolean(PREF_AUTO_ATTACH_WEB, false)
        set(value) = prefs.edit { putBoolean(PREF_AUTO_ATTACH_WEB, value) }

    var playbackMode: String
        get() = prefs.getString(PREF_PLAYBACK_MODE, "local") ?: "local"
        set(value) = prefs.edit { putString(PREF_PLAYBACK_MODE, value) }

    var injectMode: String
        get() = prefs.getString(PREF_INJECT_MODE, "noop") ?: "noop"
        set(value) = prefs.edit { putString(PREF_INJECT_MODE, value) }

    // ── Helper / Fallback logic cho WebSocket URL ───────────────────────────────

    fun resolvePrimaryWsUrl(): String? {
        return realtimeOutUrl
    }

    fun isRealtimeBridgeConfigured(): Boolean {
        return enableRealtimeBridge && resolvePrimaryWsUrl() != null
    }

    // ── Các hàm cũ giữ nguyên ────────────────────────────────────────────────────

    private fun getOptionalUint(key: String, sentinel: UInt): UInt? {
        val value = prefs.getInt(key, sentinel.toInt())
        return if (value == sentinel.toInt()) null else value.toUInt()
    }

    private fun setOptionalUint(key: String, sentinel: UInt, value: UInt?) {
        if (value == sentinel) {
            throw IllegalArgumentException("$key value cannot be $sentinel")
        }
        prefs.edit {
            if (value == null) {
                remove(key)
            } else {
                putInt(key, value.toInt())
            }
        }
    }

    var isDebugMode: Boolean
        get() = BuildConfig.DEBUG || prefs.getBoolean(PREF_DEBUG_MODE, false)
        set(enabled) = prefs.edit { putBoolean(PREF_DEBUG_MODE, enabled) }

    private var forceDirectBoot: Boolean
        get() = prefs.getBoolean(PREF_FORCE_DIRECT_BOOT, false)
        set(enabled) = prefs.edit { putBoolean(PREF_FORCE_DIRECT_BOOT, enabled) }

    private val isDirectBoot: Boolean
        get() = !userManager.isUserUnlocked || forceDirectBoot

    val directBootInProgressDir: File = File(context.filesDir, "in_progress")
    val directBootCompletedDir: File = File(context.filesDir, "completed")

    val defaultOutputDir: File = if (isDirectBoot) {
        directBootInProgressDir
    } else {
        context.getExternalFilesDir(null)!!
    }

    var outputDir: Uri?
        get() = if (isDirectBoot) {
            Uri.fromFile(directBootCompletedDir)
        } else {
            prefs.getString(PREF_OUTPUT_DIR, null)?.toUri()
        }
        set(uri) {
            if (isDirectBoot) {
                throw IllegalStateException("Changing output directory while in direct boot")
            }
            val oldUri = outputDir
            if (oldUri == uri) return
            prefs.edit {
                if (uri != null) {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    putString(PREF_OUTPUT_DIR, uri.toString())
                } else {
                    remove(PREF_OUTPUT_DIR)
                }
            }
            if (oldUri != null) {
                try {
                    context.contentResolver.releasePersistableUriPermission(
                        oldUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing persisted URI permission for: $oldUri", e)
                }
            }
            Notifications(context).dismissAll()
        }

    val outputDirOrDefault: Uri
        get() = outputDir ?: Uri.fromFile(defaultOutputDir)

    val outputDirOrDefaultIntent: Intent
        get() = Intent(Intent.ACTION_VIEW).apply {
            val uri = outputDir?.safTreeToDocument() ?: run {
                val externalDir = Environment.getExternalStorageDirectory()
                val relPath = defaultOutputDir.relativeTo(externalDir)
                DocumentsContract.buildDocumentUri(DOCUMENTSUI_AUTHORITY, "primary:$relPath")
            }
            setDataAndType(uri, "vnd.android.document/directory")
        }

    var filenameTemplate: Template?
        get() = prefs.getString(PREF_FILENAME_TEMPLATE, null)?.let { Template(it) }
        set(template) = prefs.edit {
            if (template == null) {
                remove(PREF_FILENAME_TEMPLATE)
            } else {
                putString(PREF_FILENAME_TEMPLATE, template.toString())
            }
        }

    var outputRetention: Retention?
        get() = getOptionalUint(PREF_OUTPUT_RETENTION, UInt.MAX_VALUE)?.let {
            Retention.fromRawPreferenceValue(it)
        }
        set(retention) = setOptionalUint(
            PREF_OUTPUT_RETENTION,
            UInt.MAX_VALUE,
            retention?.toRawPreferenceValue()
        )

    var websocketUrl: String?
        get() = prefs.getString(PREF_WEBSOCKET_URL, null)?.trim()?.ifBlank { null }
        set(value) = prefs.edit {
            if (value.isNullOrBlank()) {
                remove(PREF_WEBSOCKET_URL)
            } else {
                putString(PREF_WEBSOCKET_URL, value.trim())
            }
        }

    var isCallRecordingEnabled: Boolean
        get() = prefs.getBoolean(PREF_CALL_RECORDING, false)
        set(enabled) = prefs.edit { putBoolean(PREF_CALL_RECORDING, enabled) }

    var recordRules: List<RecordRule>?
        get() = prefs.getString(PREF_RECORD_RULES, null)?.let { JSON_FORMAT.decodeFromString(it) }
        set(rules) = prefs.edit {
            if (rules == null) {
                remove(PREF_RECORD_RULES)
            } else {
                putString(PREF_RECORD_RULES, JSON_FORMAT.encodeToString(rules))
            }
        }

    var format: Format?
        get() = prefs.getString(PREF_FORMAT_NAME, null)?.let { Format.getByName(it) }
        set(format) = prefs.edit {
            if (format == null) {
                remove(PREF_FORMAT_NAME)
            } else {
                putString(PREF_FORMAT_NAME, format.name)
            }
        }

    fun getFormatParam(format: Format): UInt? =
        getOptionalUint(PREF_FORMAT_PARAM_PREFIX + format.name, UInt.MAX_VALUE)

    fun setFormatParam(format: Format, param: UInt?) =
        setOptionalUint(PREF_FORMAT_PARAM_PREFIX + format.name, UInt.MAX_VALUE, param)

    fun getFormatSampleRate(format: Format): UInt? =
        getOptionalUint(PREF_FORMAT_SAMPLE_RATE_PREFIX + format.name, 0U)

    fun setFormatSampleRate(format: Format, rate: UInt?) =
        setOptionalUint(PREF_FORMAT_SAMPLE_RATE_PREFIX + format.name, 0U, rate)

    var stereo: Boolean
        get() = prefs.getBoolean(PREF_FORMAT_STEREO, false)
        set(enabled) = prefs.edit { putBoolean(PREF_FORMAT_STEREO, enabled) }

    fun resetAllFormats() {
        val keys = prefs.all.keys.filter(::isFormatKey)
        prefs.edit {
            for (key in keys) remove(key)
        }
    }

    var minDuration: Int
        get() = prefs.getInt(PREF_MIN_DURATION, 0)
        set(seconds) = prefs.edit { putInt(PREF_MIN_DURATION, seconds) }

    var writeMetadata: Boolean
        get() = prefs.getBoolean(PREF_WRITE_METADATA, false)
        set(enabled) = prefs.edit { putBoolean(PREF_WRITE_METADATA, enabled) }

    var recordTelecomApps: Boolean
        get() = prefs.getBoolean(PREF_RECORD_TELECOM_APPS, false)
        set(enabled) = prefs.edit { putBoolean(PREF_RECORD_TELECOM_APPS, enabled) }

    var recordDialingState: Boolean
        get() = prefs.getBoolean(PREF_RECORD_DIALING_STATE, false)
        set(enabled) = prefs.edit { putBoolean(PREF_RECORD_DIALING_STATE, enabled) }

    var notificationOpenDir: Boolean
        get() = prefs.getBoolean(PREF_NOTIFICATION_OPEN_DIR, false)
        set(enabled) = prefs.edit { putBoolean(PREF_NOTIFICATION_OPEN_DIR, enabled) }

    private val launcherComponent =
        ComponentName(context, SettingsActivity::class.java.name + "Launcher")

    var showLauncherIcon: Boolean
        get() = context.packageManager.getComponentEnabledSetting(launcherComponent) !=
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        set(enabled) = context.packageManager.setComponentEnabledSetting(
            launcherComponent,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )

    val nextNotificationId: Int
        get() = synchronized(context.applicationContext) {
            val nextId = prefs.getInt(PREF_NEXT_NOTIFICATION_ID, 0)
            prefs.edit { putInt(PREF_NEXT_NOTIFICATION_ID, nextId + 1) }
            nextId
        }

    private object TemplateMigrator {
        fun recurseClause(node: Template.Clause): Template.Clause = when (node) {
            is Template.StringLiteral -> node
            is Template.VariableRef -> when (node.name) {
                "phone_number" -> when (node.arg) {
                    "formatted" -> Template.VariableRef(node.name, "national")
                    "digits_only" -> Template.VariableRef(node.name, null)
                    else -> node
                }
                else -> node
            }
            is Template.Fallback -> recurseFallback(node)
        }

        fun recurseFallback(node: Template.Fallback): Template.Fallback {
            return Template.Fallback(node.choices.map(::recurseTemplateString))
        }

        fun recurseTemplateString(node: Template.TemplateString): Template.TemplateString {
            return Template.TemplateString(node.clauses.map(::recurseClause))
        }
    }

    fun migrateTemplate() {
        val template = filenameTemplate ?: return
        val migratedAst = TemplateMigrator.recurseTemplateString(template.ast)
        filenameTemplate = Template(migratedAst.toTemplate())
    }
}