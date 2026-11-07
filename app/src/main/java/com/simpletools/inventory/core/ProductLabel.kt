package com.simpletools.inventory.core

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import java.io.OutputStream

fun Item.labelCode(): String = code ?: id

fun writeProductLabel(item: Item, output: OutputStream) {
    val value = item.labelCode()
    val writer = MultiFormatWriter()
    val linear = value.length <= 24 && value.all { it in ' '..'~' }
    val matrix = if (linear) writer.encode(value, BarcodeFormat.CODE_128, 480, 90)
        else writer.encode(value, BarcodeFormat.QR_CODE, 360, 360, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
    val pdf = PdfDocument()
    try {
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val canvas = page.canvas
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f; color = Color.BLACK }
        fun line(text: String, y: Float) {
            val shown = text.replace(Regex("[\\r\\n\\t]"), " ")
            canvas.drawText(shown.take(paint.breakText(shown, true, 330f, null)), 36f, y, paint)
        }
        line(item.name, 54f)
        line(item.reference, 77f)
        val left = 36f
        val top = 96f
        val scale = minOf(300f / matrix.width, 180f / matrix.height)
        paint.isAntiAlias = false
        for (y in 0 until matrix.height) {
            var x = 0
            while (x < matrix.width) {
                if (!matrix[x, y]) {
                    x++
                    continue
                }
                val start = x
                while (x < matrix.width && matrix[x, y]) x++
                canvas.drawRect(left + start * scale, top + y * scale,
                    left + x * scale, top + (y + 1) * scale, paint)
            }
        }
        paint.isAntiAlias = true
        paint.textSize = 11f
        line(value, top + matrix.height * scale + 18f)
        pdf.finishPage(page)
        pdf.writeTo(output)
    } finally {
        pdf.close()
    }
}
