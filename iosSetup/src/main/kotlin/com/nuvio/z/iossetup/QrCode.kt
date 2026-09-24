package com.nuvio.z.iossetup

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.awt.image.BufferedImage

object QrCode {
    fun buffered(payload: String, size: Int = 360): BufferedImage {
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
        return BufferedImage(size, size, BufferedImage.TYPE_INT_RGB).also { image ->
            for (x in 0 until size) for (y in 0 until size) {
                image.setRGB(x, y, if (matrix[x, y]) 0x000000 else 0xFFFFFF)
            }
        }
    }

    fun image(payload: String, size: Int = 360): ImageBitmap = buffered(payload, size).toComposeImageBitmap()
}
