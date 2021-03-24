/*
 * Copyright 2020 Google LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.arcadan.myapplication

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import com.arcadan.myapplication.VisionProcessorBase.Companion.isCameraLiveViewportEnabled
import java.io.IOException

/**
 * Preview the camera image in the screen.
 */
class CameraSourcePreview(
    context: Context,
    attrs: AttributeSet?
) : ViewGroup(context, attrs) {

    private val surfaceView: SurfaceView = SurfaceView(context)
    private var startRequested = false
    private var surfaceAvailable = false
    private var cameraSource: CameraSource? = null
    private var overlay: GraphicOverlay? = null

    @Throws(IOException::class)
    private fun start(cameraSource: CameraSource?) {
        if (cameraSource == null) {
            stop()
        }
        this.cameraSource = cameraSource
        if (this.cameraSource != null) {
            startRequested = true
            startIfReady()
        }
    }

    @Throws(IOException::class)
    fun start(
        cameraSource: CameraSource?,
        overlay: GraphicOverlay?
    ) {
        this.overlay = overlay
        start(cameraSource)
    }

    fun stop() {
        if (cameraSource != null) {
            cameraSource!!.stop()
        }
    }

    fun release() {
        if (cameraSource != null) {
            cameraSource!!.release()
            cameraSource = null
        }
        surfaceView.holder.surface.release()
    }

    @Throws(IOException::class, SecurityException::class)
    private fun startIfReady() {
        if (startRequested && surfaceAvailable) {
            if (isCameraLiveViewportEnabled(context)) {
                cameraSource!!.start(surfaceView.holder)
            } else {
                cameraSource!!.start()
            }
            requestLayout()
            if (overlay != null) {
                val size = cameraSource!!.previewSize
                val min = Math.min(size!!.width, size.height)
                val max = Math.max(size.width, size.height)
                val isImageFlipped =
                    cameraSource!!.cameraFacing == CameraSource.CAMERA_FACING_FRONT
                if (isPortraitMode) {
                    // Swap width and height sizes when in portrait, since it will be rotated by 90 degrees.
                    // The camera preview and the image being processed have the same size.
                    overlay!!.setImageSourceInfo(min, max, isImageFlipped)
                } else {
                    overlay!!.setImageSourceInfo(max, min, isImageFlipped)
                }
                overlay!!.clear()
            }
            startRequested = false
        }
    }

    private inner class SurfaceCallback : SurfaceHolder.Callback {
        override fun surfaceCreated(surface: SurfaceHolder) {
            surfaceAvailable = true
            try {
                startIfReady()
            } catch (e: IOException) {
                Log.e(
                    Companion.TAG,
                    "Could not start camera source.",
                    e
                )
            }
        }

        override fun surfaceDestroyed(surface: SurfaceHolder) {
            surfaceAvailable = false
        }

        override fun surfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int
        ) {
        }
    }

    override fun onLayout(
        changed: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        var width = 320
        var height = 240
        if (cameraSource != null) {
            val size = cameraSource!!.previewSize
            if (size != null) {
                width = size.width
                height = size.height
            }
        }

        // Swap width and height sizes when in portrait, since it will be rotated 90 degrees
        if (isPortraitMode) {
            val tmp = width
            width = height
            height = tmp
        }
        val layoutWidth = right - left
        val layoutHeight = bottom - top

        // Computes height and width for potentially doing fit width.
        var childWidth = layoutWidth
        var childHeight =
            (layoutWidth.toFloat() / width.toFloat() * height).toInt()

        // If height is too tall using fit width, does fit height instead.
        if (childHeight > layoutHeight) {
            childHeight = layoutHeight
            childWidth = (layoutHeight.toFloat() / height.toFloat() * width) as Int
        }
        for (i in 0 until childCount) {
            getChildAt(i).layout(0, 0, childWidth, childHeight)
            Log.d(Companion.TAG, "Assigned view: $i")
        }
        try {
            startIfReady()
        } catch (e: IOException) {
            Log.e(
                Companion.TAG,
                "Could not start camera source.",
                e
            )
        }
    }

    private val isPortraitMode: Boolean
        private get() {
            val orientation = context.resources.configuration.orientation
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                return false
            }
            if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                return true
            }
            Log.d(
                Companion.TAG,
                "isPortraitMode returning false by default"
            )
            return false
        }

    companion object {
        private const val TAG = "MIDemoApp:Preview"
    }

    init {
        surfaceView.holder.addCallback(SurfaceCallback())
        addView(surfaceView)
    }
}
