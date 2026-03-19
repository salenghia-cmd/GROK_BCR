/*
 * SPDX-FileCopyrightText: 2022-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.InCallService
import android.util.Log
import androidx.annotation.StringRes
import com.chiller3.bcr.bridge.RealtimeBridgeService
import com.chiller3.bcr.extension.threadIdCompat
import com.chiller3.bcr.output.OutputFile
import kotlin.random.Random

class RecorderInCallService : InCallService(), RecorderThread.OnRecordingCompletedListener {
    companion object {
        private val TAG = RecorderInCallService::class.java.simpleName

        private const val PHONE_PACKAGE = "com.android.phone"

        private val ACTION_PAUSE = "${RecorderInCallService::class.java.canonicalName}.pause"
        private val ACTION_RESUME = "${RecorderInCallService::class.java.canonicalName}.resume"
        private val ACTION_RESTORE = "${RecorderInCallService::class.java.canonicalName}.restore"
        private val ACTION_DELETE = "${RecorderInCallService::class.java.canonicalName}.delete"

        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_NOTIFICATION_ID = "notification_id"
    }

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var notificationManager: NotificationManager
    private lateinit var prefs: Preferences
    private lateinit var notifications: Notifications

    private val foregroundNotificationId by lazy { prefs.nextNotificationId }

    private val notificationIdsToRecorders = HashMap<Int, RecorderThread>()

    private data class NotificationState(
        @StringRes val titleResId: Int,
        val message: String?,
        val actionsResIds: List<Int>,
    )

    private val allNotificationIds = HashMap<Int, NotificationState>()
    private val callsToRecorders = HashMap<Call, RecorderThread>()
    private val callsToSessionIds = HashMap<Call, String>()

    private val token = Random.nextBytes(128)

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            Log.d(TAG, "onStateChanged: $call, $state")
            handleStateChange(call, state)
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            super.onDetailsChanged(call, details)
            Log.d(TAG, "onDetailsChanged: $call, $details")
            handleDetailsChange(call, details)
            handleStateChange(call, null)
        }

        override fun onCallDestroyed(call: Call) {
            super.onCallDestroyed(call)
            Log.d(TAG, "onCallDestroyed: $call")
            requestStopRecording(call)
        }
    }

    private fun createActionIntent(notificationId: Int, action: String): Intent =
        Intent(this, RecorderInCallService::class.java).apply {
            this.action = action
            data = Uri.fromParts("notification", notificationId.toString(), null)
            putExtra(EXTRA_TOKEN, token)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        prefs = Preferences(this)
        notifications = Notifications(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val receivedToken = intent?.getByteArrayExtra(EXTRA_TOKEN)
            if (receivedToken == null || !receivedToken.contentEquals(token)) {
                throw IllegalArgumentException("Invalid token")
            }

            val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
            if (notificationId == -1) {
                throw IllegalArgumentException("Invalid notification ID")
            }

            when (val action = intent.action) {
                ACTION_PAUSE, ACTION_RESUME -> {
                    notificationIdsToRecorders[notificationId]?.isPaused = action == ACTION_PAUSE
                }

                ACTION_RESTORE, ACTION_DELETE -> {
                    notificationIdsToRecorders[notificationId]?.keepRecording =
                        if (action == ACTION_RESTORE) {
                            RecorderThread.KeepState.KEEP
                        } else {
                            RecorderThread.KeepState.DISCARD
                        }
                }

                else -> throw IllegalArgumentException("Invalid action: $action")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to handle intent: $intent", e)
        }

        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        Log.d(TAG, "onCallAdded: $call")
        call.registerCallback(callback)
        handleStateChange(call, null)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.d(TAG, "onCallRemoved: $call")
        requestStopRecording(call)
    }

    private fun handleStateChange(call: Call, state: Int?) {
        val callState = state ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            call.details.state
        } else {
            @Suppress("DEPRECATION")
            call.state
        }

        Log.d(TAG, "handleStateChange: $call, $state, $callState")

        if (call.parent != null) {
            Log.v(TAG, "Ignoring state change of conference call child")
            return
        }

        if (callState == Call.STATE_ACTIVE ||
            (prefs.recordDialingState && callState == Call.STATE_DIALING)
        ) {
            startRecording(call)
        } else if (callState == Call.STATE_DISCONNECTING ||
            callState == Call.STATE_DISCONNECTED
        ) {
            requestStopRecording(call)
        }

        callsToRecorders[call]?.isHolding = callState == Call.STATE_HOLDING

        val sessionId = callsToSessionIds[call]
        if (sessionId != null) {
            val callId = getCallId(call)
            startService(
                RealtimeBridgeService.buildStateIntent(
                    context = this,
                    sessionId = sessionId,
                    callId = callId,
                    state = callState.toString(),
                )
            )
        }
    }

    private fun startRecording(call: Call) {
        Log.i(TAG, "startRecording() called")

        if (!prefs.isCallRecordingEnabled) {
            Log.v(TAG, "Call recording is disabled")
            return
        }

        if (!Permissions.haveRequired(this)) {
            Log.v(TAG, "Required permissions have not been granted")
            return
        }

        if (callsToRecorders.containsKey(call)) {
            return
        }

        val callPackage = try {
            call.details.accountHandle?.componentName?.packageName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }

        Log.i(TAG, "Call package detected: $callPackage")

        if (callPackage != PHONE_PACKAGE) {
            Log.w(TAG, "Non-default phone package: $callPackage, but allowing it")
        }

        val callId = getCallId(call)
        val wsUrl = prefs.resolvePrimaryWsUrl()
        val sessionId = resolveRealtimeSessionId(wsUrl, callId)
        callsToSessionIds[call] = sessionId

        if (prefs.enableRealtimeBridge && !wsUrl.isNullOrBlank()) {
            startService(
                RealtimeBridgeService.buildStartIntent(
                    context = this,
                    sessionId = sessionId,
                    callId = callId,
                    wsUrl = wsUrl,
                    sampleRate = 8000,
                    channels = 1,
                )
            )
        }

        val recorder = try {
            RecorderThread(
                context = this,
                listener = this,
                parentCall = call,
                bridgeSessionId = sessionId,
            )
        } catch (e: Exception) {
            notifications.notifyRecordingFailure(e.message, null, emptyList())
            throw e
        }

        callsToRecorders[call] = recorder

        val notificationId = if (notificationIdsToRecorders.isEmpty()) {
            foregroundNotificationId
        } else {
            prefs.nextNotificationId
        }

        notificationIdsToRecorders[notificationId] = recorder
        updateForegroundState()

        Log.i(TAG, "Starting RecorderThread for call: $call")
        recorder.start()
    }

    private fun requestStopRecording(call: Call) {
        call.unregisterCallback(callback)

        val recorder = callsToRecorders[call]
        if (recorder != null) {
            recorder.cancel()
            callsToRecorders.remove(call)
        }

        val sessionId = callsToSessionIds.remove(call)
        if (sessionId != null) {
            val callId = getCallId(call)
            startService(
                RealtimeBridgeService.buildStopIntent(
                    context = this,
                    sessionId = sessionId,
                    callId = callId,
                )
            )
        }
    }

    private fun handleDetailsChange(call: Call, details: Call.Details) {
        val parentCall = call.parent
        val recorder = if (parentCall != null) {
            callsToRecorders[parentCall]
        } else {
            callsToRecorders[call]
        }
        recorder?.onCallDetailsChanged(call, details)
    }

    private fun updateForegroundState() {
        if (notificationIdsToRecorders.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }

        for (notificationId in allNotificationIds.keys.minus(notificationIdsToRecorders.keys)) {
            if (notificationId != foregroundNotificationId) {
                notificationManager.cancel(notificationId)
            }
            allNotificationIds.remove(notificationId)
        }

        if (foregroundNotificationId !in notificationIdsToRecorders) {
            val iterator = notificationIdsToRecorders.iterator()
            val (notificationId, recorder) = iterator.next()
            iterator.remove()

            notificationManager.cancel(notificationId)
            allNotificationIds.remove(notificationId)
            notificationIdsToRecorders[foregroundNotificationId] = recorder
        }

        for ((notificationId, recorder) in notificationIdsToRecorders) {
            val titleResId: Int
            val actionResIds = mutableListOf<Int>()
            val actionIntents = mutableListOf<Intent>()
            val canShowDelete: Boolean

            when (recorder.state) {
                RecorderThread.State.NOT_STARTED -> {
                    titleResId = R.string.notification_recording_initializing
                    canShowDelete = true
                }

                RecorderThread.State.RECORDING -> {
                    if (recorder.isHolding) {
                        titleResId = R.string.notification_recording_on_hold
                    } else if (recorder.isPaused) {
                        titleResId = R.string.notification_recording_paused
                        actionResIds.add(R.string.notification_action_resume)
                        actionIntents.add(createActionIntent(notificationId, ACTION_RESUME))
                    } else {
                        titleResId = R.string.notification_recording_in_progress
                        actionResIds.add(R.string.notification_action_pause)
                        actionIntents.add(createActionIntent(notificationId, ACTION_PAUSE))
                    }
                    canShowDelete = true
                }

                RecorderThread.State.FINALIZING,
                RecorderThread.State.COMPLETED -> {
                    titleResId = R.string.notification_recording_finalizing
                    canShowDelete = false
                }
            }

            val message = StringBuilder(recorder.outputPath.unredacted)

            if (canShowDelete) {
                recorder.keepRecording?.let {
                    when (it) {
                        RecorderThread.KeepState.KEEP -> {
                            actionResIds.add(R.string.notification_action_delete)
                            actionIntents.add(createActionIntent(notificationId, ACTION_DELETE))
                        }

                        RecorderThread.KeepState.DISCARD -> {
                            message.append("\n\n")
                            message.append(getString(R.string.notification_message_delete_at_end))
                            actionResIds.add(R.string.notification_action_restore)
                            actionIntents.add(createActionIntent(notificationId, ACTION_RESTORE))
                        }

                        RecorderThread.KeepState.DISCARD_TOO_SHORT -> {
                            val minDuration = prefs.minDuration
                            message.append("\n\n")
                            message.append(
                                resources.getQuantityString(
                                    R.plurals.notification_message_delete_at_end_too_short,
                                    minDuration,
                                    minDuration,
                                )
                            )
                            actionResIds.add(R.string.notification_action_restore)
                            actionIntents.add(createActionIntent(notificationId, ACTION_RESTORE))
                        }
                    }
                }
            }

            val state = NotificationState(
                titleResId = titleResId,
                message = message.toString(),
                actionsResIds = actionResIds,
            )

            if (state == allNotificationIds[notificationId]) {
                continue
            }

            val notification = notifications.createPersistentNotification(
                state.titleResId,
                state.message,
                state.actionsResIds.zip(actionIntents),
            )

            if (notificationId == foregroundNotificationId) {
                startForeground(notificationId, notification)
            } else {
                notificationManager.notify(notificationId, notification)
            }

            allNotificationIds[notificationId] = state
        }

        notifications.vibrateIfEnabled(Notifications.CHANNEL_ID_PERSISTENT)
    }

    private fun onRecorderExited(recorder: RecorderThread) {
        val call = callsToRecorders.entries.find { it.value === recorder }?.key
        if (call != null) {
            Log.w(TAG, "$recorder exited before cancellation")
            callsToRecorders.remove(call)
            requestStopRecording(call)
        }

        assert(notificationIdsToRecorders.entries.removeIf { it.value === recorder }) {
            "$recorder not found"
        }

        updateForegroundState()
    }

    override fun onRecordingStateChanged(thread: RecorderThread) {
        handler.post { updateForegroundState() }
    }

    override fun onRecordingCompleted(
        thread: RecorderThread,
        file: OutputFile?,
        additionalFiles: List<OutputFile>,
        status: RecorderThread.Status,
    ) {
        Log.i(TAG, "Recording completed: ${thread.threadIdCompat}: ${file?.redacted}: $status")

        handler.post {
            onRecorderExited(thread)

            val firstMoveError = file?.moveError ?: additionalFiles.firstNotNullOfOrNull { it.moveError }
            if (firstMoveError != null) {
                notifications.notifyMoveFailure(firstMoveError.localizedMessage)
            }

            when (status) {
                RecorderThread.Status.Succeeded -> {
                    notifications.notifyRecordingSuccess(file!!, additionalFiles)
                }

                is RecorderThread.Status.Failed -> {
                    val message = buildString {
                        when (status.component) {
                            is RecorderThread.FailureComponent.AndroidMedia -> {
                                val frame = status.component.stackFrame
                                append(
                                    getString(
                                        R.string.notification_internal_android_error,
                                        "${frame.className}.${frame.methodName}",
                                    )
                                )
                            }

                            RecorderThread.FailureComponent.Other -> {
                            }
                        }

                        status.exception?.localizedMessage?.let {
                            if (isNotEmpty()) {
                                append("\n\n")
                            }
                            append(it)
                        }
                    }

                    notifications.notifyRecordingFailure(message, file, additionalFiles)
                }

                is RecorderThread.Status.Discarded -> {
                    when (status.reason) {
                        RecorderThread.DiscardReason.Intentional -> {
                        }

                        is RecorderThread.DiscardReason.Silence -> {
                            notifications.notifyRecordingPureSilence(status.reason.callPackage)
                        }
                    }
                }

                RecorderThread.Status.Cancelled -> {
                }
            }
        }
    }

    private fun getCallId(call: Call): String {
        return try {
            call.details.handle?.schemeSpecificPart ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun resolveRealtimeSessionId(wsUrl: String?, callId: String): String {
        if (!wsUrl.isNullOrBlank()) {
            try {
                val session = Uri.parse(wsUrl).getQueryParameter("session")?.trim()
                if (!session.isNullOrEmpty()) {
                    return session
                }
            } catch (_: Throwable) {
            }
        }

        return RealtimeBridgeService.ensureSessionId(callId)
    }
}