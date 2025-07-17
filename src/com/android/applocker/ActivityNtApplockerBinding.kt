/*
 * Copyright (C) 2025 The AxionAOSP Project
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
package com.android.applocker

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.applocker.R

class ActivityNtApplockerBinding private constructor(
    val root: FrameLayout,
    val backgroundView: FrameLayout,
    val fullscreenContent: TextView,
    val fullscreenContentControls: LinearLayout,
    val pipLockIcon: ImageView
) {

    fun clear() {
        fullscreenContent.setOnClickListener(null)
        fullscreenContent.text = null
        fullscreenContent.background = null

        pipLockIcon.setImageDrawable(null)
        pipLockIcon.setOnClickListener(null)

        fullscreenContentControls.removeAllViews()
        backgroundView.background = null
    }

    companion object {

        @JvmStatic
        fun inflate(inflater: LayoutInflater): ActivityNtApplockerBinding {
            return inflate(inflater, null, false)
        }

        @JvmStatic
        fun inflate(inflater: LayoutInflater, parent: ViewGroup?, attachToParent: Boolean): ActivityNtApplockerBinding {
            val view = inflater.inflate(R.layout.activity_nt_applocker, parent, false)
            if (attachToParent && parent != null) {
                parent.addView(view)
            }
            return bind(view)
        }

        @JvmStatic
        fun bind(view: View): ActivityNtApplockerBinding {
            val root = view as? FrameLayout
                ?: throw IllegalArgumentException("Root view must be a FrameLayout")

            val fullscreenContent = view.findViewById<TextView>(R.id.fullscreen_content)
                ?: throw NullPointerException("Missing TextView with ID fullscreen_content")

            val fullscreenContentControls = view.findViewById<LinearLayout>(R.id.fullscreen_content_controls)
                ?: throw NullPointerException("Missing LinearLayout with ID fullscreen_content_controls")

            val pipLockIcon = view.findViewById<ImageView>(R.id.pip_lock_icon)
                ?: throw NullPointerException("Missing ImageView with ID pip_lock_icon")

            return ActivityNtApplockerBinding(
                root = root,
                backgroundView = root,
                fullscreenContent = fullscreenContent,
                fullscreenContentControls = fullscreenContentControls,
                pipLockIcon = pipLockIcon
            )
        }
    }
}
