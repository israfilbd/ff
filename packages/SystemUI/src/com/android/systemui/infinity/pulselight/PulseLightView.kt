/*
 * Copyright (C) 2023-2024 The LibreMobileOS Foundation
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

package com.android.systemui.infinity.pulselight

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.UserHandle
import android.provider.Settings
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.RelativeLayout
import androidx.core.view.isVisible
import androidx.palette.graphics.Palette
import com.android.settingslib.Utils
import com.android.systemui.R
import com.android.systemui.people.PeopleSpaceUtils

class PulseLightView @JvmOverloads constructor(
    context: Context?,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : RelativeLayout(context, attrs, defStyleAttr, defStyleRes), Animator.AnimatorListener {

    private var lightAnimator: ValueAnimator? = null
    private var onlyWhenFaceDown = false
    private val onlyWhenFaceDownDefault by lazy {
        context?.resources?.getBoolean(
            com.android.internal.R.bool.config_edgeLightFaceDownEnabledByDefault
        ) ?: false
    }

    init {
        context?.let { setupContentObserver(it) }
    }

    private fun setupContentObserver(context: Context) {
        val pulseAmbientLightFaceDown = Settings.Secure.getUriFor(PULSE_AMBIENT_LIGHT_FACE_DOWN)
        val contentObserver = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                onlyWhenFaceDown = Settings.Secure.getIntForUser(
                    context.contentResolver,
                    PULSE_AMBIENT_LIGHT_FACE_DOWN,
                    if (onlyWhenFaceDownDefault) 1 else 0,
                    UserHandle.USER_CURRENT
                ) != 0
                updateBackgroundColor()
            }
        }
        context.contentResolver.registerContentObserver(
            pulseAmbientLightFaceDown, false, contentObserver, UserHandle.USER_CURRENT
        )
        contentObserver.onChange(true, pulseAmbientLightFaceDown)
    }

    private fun updateBackgroundColor() {
        val bgColor = if (onlyWhenFaceDown) Color.BLACK else Color.TRANSPARENT
        setBackgroundColor(bgColor)
    }

    override fun onAnimationStart(animator: Animator) {
        // No-op
    }

    override fun onAnimationEnd(animator: Animator) {
        // No-op
    }

    override fun onAnimationCancel(animator: Animator) {
        // No-op
    }

    override fun onAnimationRepeat(animator: Animator) {
        // No-op
    }

    fun startAnimation(notificationPackageName: String) {
        isVisible = true
        val lightDuration = Settings.Secure.getIntForUser(
            context?.contentResolver,
            Settings.Secure.PULSE_AMBIENT_LIGHT_DURATION, 2,
            UserHandle.USER_CURRENT
        ) * 1000L

        val repeat = Settings.Secure.getIntForUser(
            context?.contentResolver,
            Settings.Secure.PULSE_AMBIENT_LIGHT_REPEAT_COUNT, 0,
            UserHandle.USER_CURRENT
        )
        val width = Settings.Secure.getIntForUser(
            context?.contentResolver,
            Settings.Secure.PULSE_AMBIENT_LIGHT_WIDTH, 125,
            UserHandle.USER_CURRENT
        )
        val pulseAmbientLayout = Settings.Secure.getIntForUser(
            context?.contentResolver,
            Settings.Secure.PULSE_AMBIENT_LIGHT_LAYOUT, 0,
            UserHandle.USER_CURRENT
        )
        val directionIsRestart = Settings.Secure.getIntForUser(
            context?.contentResolver,
            Settings.Secure.PULSE_AMBIENT_LIGHT_REPEAT_DIRECTION, 0,
            UserHandle.USER_CURRENT
        ) != 1

        val color = getLightColor(notificationPackageName)
        val leftView = requireViewById<ImageView>(R.id.animation_left)
        val rightView = requireViewById<ImageView>(R.id.animation_right)

        leftView.adjustViewBounds = true
        leftView.scaleType = ImageView.ScaleType.FIT_XY
        rightView.adjustViewBounds = true
        rightView.scaleType = ImageView.ScaleType.FIT_XY

        when (pulseAmbientLayout) {
            1 -> {
                leftView.setImageResource(R.drawable.aod_notification_light_left_solid)
                rightView.setImageResource(R.drawable.aod_notification_light_right_solid)
            }
            else -> {
                leftView.setImageResource(R.drawable.aod_notification_light_left)
                rightView.setImageResource(R.drawable.aod_notification_light_right)
            }
        }

        leftView.setColorFilter(color)
        rightView.setColorFilter(color)
        leftView.layoutParams.width = width
        rightView.layoutParams.width = width

        lightAnimator = ValueAnimator.ofFloat(0.0f, 2.0f).apply {
            duration = lightDuration
            repeatCount = repeat
            repeatMode = if (directionIsRestart) ValueAnimator.RESTART else ValueAnimator.REVERSE
            addListener(this@PulseLightView)
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                leftView.scaleY = progress
                rightView.scaleY = progress

                var alpha = 1.0f
                if (progress <= 0.3f) {
                    alpha = progress / 0.3f
                } else if (progress >= 1.0f) {
                    alpha = 2.0f - progress
                }
                leftView.alpha = alpha
                rightView.alpha = alpha
            }
            start()
        }
    }

    fun stopAnimation() {
        isVisible = false
        lightAnimator?.cancel()
        lightAnimator = null
    }

    private fun getLightColor(notificationPackageName: String): Int {
        val colorMode = Settings.Secure.getIntForUser(
            context?.contentResolver,
            Settings.Secure.PULSE_AMBIENT_LIGHT_COLOR_MODE, 1,
            UserHandle.USER_CURRENT
        )
        return when (colorMode) {
            COLOR_MODE_APP -> {
                try {
                    val iconDrawable: Drawable? = context?.packageManager
                        ?.getApplicationIcon(notificationPackageName)
                    val bitmap: Bitmap? = PeopleSpaceUtils.convertDrawableToBitmap(iconDrawable)
                    bitmap?.let {
                        Palette.from(it).generate().getDominantColor(Color.TRANSPARENT)
                    } ?: Color.TRANSPARENT
                } catch (e: Exception) {
                    Color.TRANSPARENT
                }
            }
            COLOR_MODE_AUTO -> Utils.getColorAccentDefaultColor(context)
            else -> Settings.Secure.getIntForUser(
                context?.contentResolver,
                Settings.Secure.PULSE_AMBIENT_LIGHT_COLOR, -9777409,
                UserHandle.USER_CURRENT
            )
        }
    }

    companion object {
        private const val COLOR_MODE_APP = 0
        private const val COLOR_MODE_AUTO = 1
        private const val PULSE_AMBIENT_LIGHT_FACE_DOWN = Settings.Secure.PULSE_AMBIENT_LIGHT_FACE_DOWN
    }
}
