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

import android.annotation.TargetApi
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image.Plane
import android.net.Uri
import android.os.Build.VERSION_CODES
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.camera.core.ImageProxy
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Utils functions for bitmap conversions.
 */
object BitmapUtils {
    private const val TAG = "BitmapUtils"

    /**
     * Converts NV21 format byte buffer to bitmap.
     */
    fun getBitmap(data: ByteBuffer, metadata: FrameMetadata): Bitmap? {
        data.rewind()
        val imageInBuffer = ByteArray(data.limit())
        data[imageInBuffer, 0, imageInBuffer.size]
        try {
            val image = YuvImage(
                imageInBuffer,
                ImageFormat.NV21,
                metadata.width,
                metadata.height,
                null
            )
            val stream = ByteArrayOutputStream()
            image.compressToJpeg(
                Rect(0, 0, metadata.width, metadata.height),
                80,
                stream
            )
            val bmp = BitmapFactory.decodeByteArray(stream.toByteArray(), 0, stream.size())
            stream.close()
            return rotateBitmap(bmp, metadata.rotation, false, false)
        } catch (e: Exception) {
            Log.e("VisionProcessorBase", "Error: " + e.message)
        }
        return null
    }

    /**
     * Converts a YUV_420_888 image from CameraX API to a bitmap.
     */
    @RequiresApi(VERSION_CODES.KITKAT)
    fun getBitmap(image: ImageProxy): Bitmap? {
        val frameMetadata = FrameMetadata.Builder()
            .setWidth(image.width)
            .setHeight(image.height)
            .setRotation(image.imageInfo.rotationDegrees)
            .build()
        val nv21Buffer = yuv420ThreePlanesToNV21(
            image.image!!.planes, image.width, image.height
        )
        return getBitmap(nv21Buffer, frameMetadata)
    }

    /**
     * Rotates a bitmap if it is converted from a bytebuffer.
     */
    private fun rotateBitmap(
        bitmap: Bitmap,
        rotationDegrees: Int,
        flipX: Boolean,
        flipY: Boolean
    ): Bitmap {
        val matrix = Matrix()

        // Rotate the image back to straight.
        matrix.postRotate(rotationDegrees.toFloat())

        // Mirror the image along the X or Y axis.
        matrix.postScale(if (flipX) -1.0f else 1.0f, if (flipY) -1.0f else 1.0f)
        val rotatedBitmap =
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

        // Recycle the old bitmap if it has changed.
        if (rotatedBitmap != bitmap) {
            bitmap.recycle()
        }
        return rotatedBitmap
    }

    fun getBitmapFromAsset(context: Context, fileName: String): Bitmap? {
        var inputStream: InputStream? = null
        try {
            inputStream = context.assets.open(fileName)
            return BitmapFactory.decodeStream(inputStream)
        } catch (e: IOException) {
            Log.e(TAG, "Error reading asset: $fileName", e)
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close()
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to close input stream: ", e)
                }
            }
        }
        return null
    }

    @Throws(IOException::class)
    fun getBitmapFromContentUri(
        contentResolver: ContentResolver,
        imageUri: Uri
    ): Bitmap? {
        val decodedBitmap =
            MediaStore.Images.Media.getBitmap(contentResolver, imageUri) ?: return null
        val orientation = getExifOrientationTag(contentResolver, imageUri)
        var rotationDegrees = 0
        var flipX = false
        var flipY = false
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> flipX = true
            ExifInterface.ORIENTATION_ROTATE_90 -> rotationDegrees = 90
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                rotationDegrees = 90
                flipX = true
            }
            ExifInterface.ORIENTATION_ROTATE_180 -> rotationDegrees =
                180
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> flipY = true
            ExifInterface.ORIENTATION_ROTATE_270 -> rotationDegrees =
                -90
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                rotationDegrees = -90
                flipX = true
            }
            ExifInterface.ORIENTATION_UNDEFINED, ExifInterface.ORIENTATION_NORMAL -> {
            }
            else -> {
            }
        }
        return rotateBitmap(decodedBitmap, rotationDegrees, flipX, flipY)
    }

    private fun getExifOrientationTag(resolver: ContentResolver, imageUri: Uri): Int {
        // We only support parsing EXIF orientation tag from local file on the device.
        // See also:
        // https://android-developers.googleblog.com/2016/12/introducing-the-exifinterface-support-library.html
        if (ContentResolver.SCHEME_CONTENT != imageUri.scheme &&
            ContentResolver.SCHEME_FILE != imageUri.scheme
        ) {
            return 0
        }
        lateinit var exif: ExifInterface
        try {
            resolver.openInputStream(imageUri).use { inputStream ->
                if (inputStream == null) {
                    return 0
                }
                exif = ExifInterface(inputStream)
            }
        } catch (e: IOException) {
            Log.e(
                TAG,
                "failed to open file to read rotation meta data: $imageUri",
                e
            )
            return 0
        }
        return exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    }

    fun convertBitmapToNv21Buffer(bitmap: Bitmap): ByteBuffer {
        return ByteBuffer.wrap(convertBitmapToNv21Bytes(bitmap))
    }

    fun convertBitmapToNv21Bytes(bitmap: Bitmap): ByteArray {
        val inputWidth = bitmap.width
        val inputHeight = bitmap.height
        val argb = IntArray(inputWidth * inputHeight)
        bitmap.getPixels(argb, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        val nv21Bytes = ByteArray(
            inputHeight * inputWidth +
                    2 * Math.ceil(inputHeight / 2.0).toInt() * Math.ceil(
                inputWidth / 2.0
            ).toInt()
        )
        encodeToNv21(nv21Bytes, argb, inputWidth, inputHeight)
        return nv21Bytes
    }

    private fun encodeToNv21(
        nv21Bytes: ByteArray,
        argb: IntArray,
        width: Int,
        height: Int
    ) {
        val frameSize = width * height
        var yIndex = 0
        var uvIndex = frameSize
        var red: Int
        var green: Int
        var blue: Int
        var y: Int
        var u: Int
        var v: Int
        var index = 0
        for (j in 0 until height) {
            for (i in 0 until width) {

                // first byte is alpha, but is unused
                red = argb[index] and 0xff0000 shr 16
                green = argb[index] and 0xff00 shr 8
                blue = argb[index] and 0xff shr 0

                // well known RGB to YUV algorithm
                y = (66 * red + 129 * green + 25 * blue + 128 shr 8) + 16
                u = (-38 * red - 74 * green + 112 * blue + 128 shr 8) + 128
                v = (112 * red - 94 * green - 18 * blue + 128 shr 8) + 128

                // NV21 has a plane of Y and interleaved planes of VU each sampled by a factor of 2
                // meaning for every 4 Y pixels there are 1 V and 1 U.  Note the sampling is every other
                // pixel AND every other scanline.
                nv21Bytes[yIndex++] =
                    (if (y < 0) 0 else if (y > 255) 255 else y).toByte()
                if (j % 2 == 0 && index % 2 == 0) {
                    nv21Bytes[uvIndex++] =
                        (if (v < 0) 0 else if (v > 255) 255 else v).toByte()
                    nv21Bytes[uvIndex++] =
                        (if (u < 0) 0 else if (u > 255) 255 else u).toByte()
                }
                index++
            }
        }
    }

    fun convertBitmapToYv12Buffer(bitmap: Bitmap): ByteBuffer {
        return ByteBuffer.wrap(convertBitmapToYv12Bytes(bitmap))
    }

    fun convertBitmapToYv12Bytes(bitmap: Bitmap): ByteArray {
        val nv21Bytes = convertBitmapToNv21Bytes(bitmap)
        return nv21Toyv12(nv21Bytes)
    }

    /**
     * Converts nv21 byte[] to yv12 byte[].
     *
     *
     * NV21 (4:2:0) Y Y Y Y Y Y Y Y Y Y Y Y Y Y Y Y Y Y Y Y Y Y Y Y V U V U V U V U V U V U
     *
     *
     * YV12 (4:2:0) Y Y Y Y Y Y Y Y Y Y Y Y Y Y Y Y Y Y Y Y Y Y Y Y V V V V V V U U U U U U
     */
    private fun nv21Toyv12(nv21Bytes: ByteArray): ByteArray {
        val totalBytes = nv21Bytes.size
        val rowSize = totalBytes / 6 // 4+2+0
        val yv12Bytes = ByteArray(totalBytes)
        System.arraycopy(nv21Bytes, 0, yv12Bytes, 0, rowSize * 4)
        val offSet = totalBytes / 6 * 4
        for (i in 0 until rowSize) {
            yv12Bytes[offSet + i] = nv21Bytes[offSet + 2 * i] // V
            yv12Bytes[offSet + rowSize + i] = nv21Bytes[offSet + 2 * i + 1] // U
        }
        return yv12Bytes
    }

    /**
     * Converts YUV_420_888 to NV21 bytebuffer.
     *
     *
     * The NV21 format consists of a single byte array containing the Y, U and V values. For an
     * image of size S, the first S positions of the array contain all the Y values. The remaining
     * positions contain interleaved V and U values. U and V are subsampled by a factor of 2 in both
     * dimensions, so there are S/4 U values and S/4 V values. In summary, the NV21 array will contain
     * S Y values followed by S/4 VU values: YYYYYYYYYYYYYY(...)YVUVUVUVU(...)VU
     *
     *
     * YUV_420_888 is a generic format that can describe any YUV image where U and V are subsampled
     * by a factor of 2 in both dimensions. [Image.getPlanes] returns an array with the Y, U and
     * V planes. The Y plane is guaranteed not to be interleaved, so we can just copy its values into
     * the first part of the NV21 array. The U and V planes may already have the representation in the
     * NV21 format. This happens if the planes share the same buffer, the V buffer is one position
     * before the U buffer and the planes have a pixelStride of 2. If this is case, we can just copy
     * them to the NV21 array.
     */
    @RequiresApi(VERSION_CODES.KITKAT)
    private fun yuv420ThreePlanesToNV21(
        yuv420888planes: Array<Plane>,
        width: Int,
        height: Int
    ): ByteBuffer {
        val imageSize = width * height
        val out = ByteArray(imageSize + 2 * (imageSize / 4))
        if (areUVPlanesNV21(yuv420888planes, width, height)) {
            // Copy the Y values.
            yuv420888planes[0].buffer[out, 0, imageSize]
            val uBuffer = yuv420888planes[1].buffer
            val vBuffer = yuv420888planes[2].buffer
            // Get the first V value from the V buffer, since the U buffer does not contain it.
            vBuffer[out, imageSize, 1]
            // Copy the first U value and the remaining VU values from the U buffer.
            uBuffer[out, imageSize + 1, 2 * imageSize / 4 - 1]
        } else {
            // Fallback to copying the UV values one by one, which is slower but also works.
            // Unpack Y.
            unpackPlane(yuv420888planes[0], width, height, out, 0, 1)
            // Unpack U.
            unpackPlane(yuv420888planes[1], width, height, out, imageSize + 1, 2)
            // Unpack V.
            unpackPlane(yuv420888planes[2], width, height, out, imageSize, 2)
        }
        return ByteBuffer.wrap(out)
    }

    /**
     * Checks if the UV plane buffers of a YUV_420_888 image are in the NV21 format.
     */
    @RequiresApi(VERSION_CODES.KITKAT)
    private fun areUVPlanesNV21(
        planes: Array<Plane>,
        width: Int,
        height: Int
    ): Boolean {
        val imageSize = width * height
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        // Backup buffer properties.
        val vBufferPosition = vBuffer.position()
        val uBufferLimit = uBuffer.limit()

        // Advance the V buffer by 1 byte, since the U buffer will not contain the first V value.
        vBuffer.position(vBufferPosition + 1)
        // Chop off the last byte of the U buffer, since the V buffer will not contain the last U value.
        uBuffer.limit(uBufferLimit - 1)

        // Check that the buffers are equal and have the expected number of elements.
        val areNV21 =
            vBuffer.remaining() == 2 * imageSize / 4 - 2 && vBuffer.compareTo(uBuffer) == 0

        // Restore buffers to their initial state.
        vBuffer.position(vBufferPosition)
        uBuffer.limit(uBufferLimit)
        return areNV21
    }

    /**
     * Unpack an image plane into a byte array.
     *
     *
     * The input plane data will be copied in 'out', starting at 'offset' and every pixel will be
     * spaced by 'pixelStride'. Note that there is no row padding on the output.
     */
    @TargetApi(VERSION_CODES.KITKAT)
    private fun unpackPlane(
        plane: Plane,
        width: Int,
        height: Int,
        out: ByteArray,
        offset: Int,
        pixelStride: Int
    ) {
        val buffer = plane.buffer
        buffer.rewind()

        // Compute the size of the current plane.
        // We assume that it has the aspect ratio as the original image.
        val numRow = (buffer.limit() + plane.rowStride - 1) / plane.rowStride
        if (numRow == 0) {
            return
        }
        val scaleFactor = height / numRow
        val numCol = width / scaleFactor

        // Extract the data in the output buffer.
        var outputPos = offset
        var rowStart = 0
        for (row in 0 until numRow) {
            var inputPos = rowStart
            for (col in 0 until numCol) {
                out[outputPos] = buffer[inputPos]
                outputPos += pixelStride
                inputPos += plane.pixelStride
            }
            rowStart += plane.rowStride
        }
    }
}
