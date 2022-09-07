/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.temporarydisplay

import android.annotation.LayoutRes
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS
import android.view.accessibility.AccessibilityManager.FLAG_CONTENT_ICONS
import android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT
import androidx.annotation.CallSuper
import com.android.internal.widget.CachingIconView
import com.android.settingslib.Utils
import com.android.systemui.R
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.media.taptotransfer.common.MediaTttLogger
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.concurrency.DelayableExecutor

/**
 * A generic controller that can temporarily display a new view in a new window.
 *
 * Subclasses need to override and implement [updateView], which is where they can control what
 * gets displayed to the user.
 *
 * The generic type T is expected to contain all the information necessary for the subclasses to
 * display the view in a certain state, since they receive <T> in [updateView].
 *
 * TODO(b/245610654): Remove all the media-specific logic from this class.
 */
abstract class TemporaryViewDisplayController<T : TemporaryViewInfo>(
    internal val context: Context,
    internal val logger: MediaTttLogger,
    internal val windowManager: WindowManager,
    @Main private val mainExecutor: DelayableExecutor,
    private val accessibilityManager: AccessibilityManager,
    private val configurationController: ConfigurationController,
    private val powerManager: PowerManager,
    @LayoutRes private val viewLayoutRes: Int,
) {
    /**
     * Window layout params that will be used as a starting point for the [windowLayoutParams] of
     * all subclasses.
     */
    @SuppressLint("WrongConstant") // We're allowed to use TYPE_VOLUME_OVERLAY
    internal val commonWindowLayoutParams = WindowManager.LayoutParams().apply {
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        type = WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY
        flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        title = WINDOW_TITLE
        format = PixelFormat.TRANSLUCENT
        setTrustedOverlay()
    }

    /**
     * The window layout parameters we'll use when attaching the view to a window.
     *
     * Subclasses must override this to provide their specific layout params, and they should use
     * [commonWindowLayoutParams] as part of their layout params.
     */
    internal abstract val windowLayoutParams: WindowManager.LayoutParams

    /** The view currently being displayed. Null if the view is not being displayed. */
    private var view: ViewGroup? = null

    /** The info currently being displayed. Null if the view is not being displayed. */
    internal var info: T? = null

    /** A [Runnable] that, when run, will cancel the pending timeout of the view. */
    private var cancelViewTimeout: Runnable? = null

    /**
     * Displays the view with the provided [newInfo].
     *
     * This method handles inflating and attaching the view, then delegates to [updateView] to
     * display the correct information in the view.
     */
    fun displayView(newInfo: T) {
        val currentView = view

        if (currentView != null) {
            updateView(newInfo, currentView)
        } else {
            // The view is new, so set up all our callbacks and inflate the view
            configurationController.addCallback(displayScaleListener)
            // Wake the screen if necessary so the user will see the view. (Per b/239426653, we want
            // the view to show over the dream state, so we should only wake up if the screen is
            // completely off.)
            if (!powerManager.isScreenOn) {
                powerManager.wakeUp(
                        SystemClock.uptimeMillis(),
                        PowerManager.WAKE_REASON_APPLICATION,
                        "com.android.systemui:media_tap_to_transfer_activated"
                )
            }

            inflateAndUpdateView(newInfo)
        }

        // Cancel and re-set the view timeout each time we get a new state.
        val timeout = accessibilityManager.getRecommendedTimeoutMillis(
            newInfo.getTimeoutMs().toInt(),
            // Not all views have controls so FLAG_CONTENT_CONTROLS might be superfluous, but
            // include it just to be safe.
            FLAG_CONTENT_ICONS or FLAG_CONTENT_TEXT or FLAG_CONTENT_CONTROLS
       )
        cancelViewTimeout?.run()
        cancelViewTimeout = mainExecutor.executeDelayed(
            { removeView(TemporaryDisplayRemovalReason.REASON_TIMEOUT) },
            timeout.toLong()
        )
    }

    /** Inflates a new view, updates it with [newInfo], and adds the view to the window. */
    private fun inflateAndUpdateView(newInfo: T) {
        val newView = LayoutInflater
                .from(context)
                .inflate(viewLayoutRes, null) as ViewGroup
        view = newView
        updateView(newInfo, newView)
        windowManager.addView(newView, windowLayoutParams)
        animateViewIn(newView)
    }

    /** Removes then re-inflates the view. */
    private fun reinflateView() {
        val currentInfo = info
        if (view == null || currentInfo == null) { return }

        windowManager.removeView(view)
        inflateAndUpdateView(currentInfo)
    }

    private val displayScaleListener = object : ConfigurationController.ConfigurationListener {
        override fun onDensityOrFontScaleChanged() {
            reinflateView()
        }
    }

    /**
     * Hides the view.
     *
     * @param removalReason a short string describing why the view was removed (timeout, state
     *     change, etc.)
     */
    open fun removeView(removalReason: String) {
        if (view == null) { return }
        logger.logChipRemoval(removalReason)
        configurationController.removeCallback(displayScaleListener)
        windowManager.removeView(view)
        view = null
        info = null
        // No need to time the view out since it's already gone
        cancelViewTimeout?.run()
    }

    /**
     * A method implemented by subclasses to update [currentView] based on [newInfo].
     */
    @CallSuper
    open fun updateView(newInfo: T, currentView: ViewGroup) {
        info = newInfo
    }

    /**
     * A method that can be implemented by subclasses to do custom animations for when the view
     * appears.
     */
    open fun animateViewIn(view: ViewGroup) {}

    /**
     * Returns the size that the icon should be, or null if no size override is needed.
     */
    open fun getIconSize(isAppIcon: Boolean): Int? = null

    /**
     * An internal method to set the icon on the view.
     *
     * This is in the common superclass since both the sender and the receiver show an icon.
     *
     * @param appPackageName the package name of the app playing the media. Will be used to fetch
     *   the app icon and app name if overrides aren't provided.
     *
     * @return the content description of the icon.
     */
    internal fun setIcon(
        currentView: ViewGroup,
        appPackageName: String?,
        appIconDrawableOverride: Drawable? = null,
        appNameOverride: CharSequence? = null,
    ): CharSequence {
        val appIconView = currentView.requireViewById<CachingIconView>(R.id.app_icon)
        val iconInfo = getIconInfo(appPackageName)

        getIconSize(iconInfo.isAppIcon)?.let { size ->
            val lp = appIconView.layoutParams
            lp.width = size
            lp.height = size
            appIconView.layoutParams = lp
        }

        appIconView.contentDescription = appNameOverride ?: iconInfo.iconName
        appIconView.setImageDrawable(appIconDrawableOverride ?: iconInfo.icon)
        return appIconView.contentDescription
    }

    /**
     * Returns the information needed to display the icon.
     *
     * The information will either contain app name and icon of the app playing media, or a default
     * name and icon if we can't find the app name/icon.
     */
    private fun getIconInfo(appPackageName: String?): IconInfo {
        if (appPackageName != null) {
            try {
                return IconInfo(
                    iconName = context.packageManager.getApplicationInfo(
                        appPackageName, PackageManager.ApplicationInfoFlags.of(0)
                    ).loadLabel(context.packageManager).toString(),
                    icon = context.packageManager.getApplicationIcon(appPackageName),
                    isAppIcon = true
                )
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Cannot find package $appPackageName", e)
            }
        }
        return IconInfo(
            iconName = context.getString(R.string.media_output_dialog_unknown_launch_app_name),
            icon = context.resources.getDrawable(R.drawable.ic_cast).apply {
                this.setTint(
                    Utils.getColorAttrDefaultColor(context, android.R.attr.textColorPrimary)
                )
            },
            isAppIcon = false
        )
    }
}

// Used in CTS tests UpdateMediaTapToTransferSenderDisplayTest and
// UpdateMediaTapToTransferReceiverDisplayTest
private const val WINDOW_TITLE = "Media Transfer Chip View"
private val TAG = TemporaryViewDisplayController::class.simpleName!!

object TemporaryDisplayRemovalReason {
    const val REASON_TIMEOUT = "TIMEOUT"
    const val REASON_SCREEN_TAP = "SCREEN_TAP"
}

private data class IconInfo(
    val iconName: String,
    val icon: Drawable,
    /** True if [icon] is the app's icon, and false if [icon] is some generic default icon. */
    val isAppIcon: Boolean
)
