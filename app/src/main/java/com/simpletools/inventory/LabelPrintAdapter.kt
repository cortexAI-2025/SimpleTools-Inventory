package com.simpletools.inventory

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

internal class LabelPrintAdapter(private val pdf: File) : PrintDocumentAdapter() {
    private val worker = Executors.newSingleThreadExecutor()

    override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, cancel: CancellationSignal,
        callback: LayoutResultCallback, extras: android.os.Bundle?) {
        if (cancel.isCanceled) callback.onLayoutCancelled()
        else callback.onLayoutFinished(PrintDocumentInfo.Builder("simpletools-label.pdf")
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(1).build(), old != new)
    }

    override fun onWrite(pages: Array<PageRange>, destination: ParcelFileDescriptor,
        cancel: CancellationSignal, callback: WriteResultCallback) {
        worker.execute {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(destination).use { output ->
                    if (!cancel.isCanceled) pdf.inputStream().use { it.copyTo(output) }
                }
                if (cancel.isCanceled) callback.onWriteCancelled()
                else callback.onWriteFinished(arrayOf(PageRange(0, 0)))
            } catch (_: IOException) {
                if (cancel.isCanceled) callback.onWriteCancelled()
                else callback.onWriteFailed(null)
            }
        }
    }

    override fun onFinish() {
        worker.execute { pdf.delete() }
        worker.shutdown()
    }
}
