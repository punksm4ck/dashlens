package app.dashlens

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Reads the screen from a screenshot, for apps that hide their offer card from the accessibility read (Uber).
 * Needs Android 11+ and the accessibility service's screenshot ability. The recognition model is bundled in the app and runs
 * on the phone: the picture is never saved and never leaves the device (DashLens has no internet permission).
 */
object OfferOcr {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /**
     * Takes one screenshot and reads its text. [done] gets the lines found, or null plus a short reason when anything failed.
     * Always called on the main thread.
     */
    fun read(service: AccessibilityService, done: (List<OcrLine>?, String) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { done(null, "needs Android 11 or newer"); return }
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    var bmp: Bitmap? = null
                    try {
                        val hw = screenshot.hardwareBuffer
                        val wrapped = Bitmap.wrapHardwareBuffer(hw, screenshot.colorSpace)
                        bmp = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                        wrapped?.recycle()
                        hw.close()
                    } catch (e: Exception) {
                        done(null, "screenshot could not be copied (" + e.javaClass.simpleName + ")")
                        return
                    }
                    val ready = bmp
                    if (ready == null) { done(null, "screenshot was empty"); return }
                    recognize(ready, done)
                }

                override fun onFailure(errorCode: Int) {
                    done(null, "Android refused the screenshot (code " + errorCode + ")")
                }
            })
        } catch (e: Exception) {
            done(null, "screenshot not available (" + e.javaClass.simpleName + ")")
        }
    }

    private fun recognize(bmp: Bitmap, done: (List<OcrLine>?, String) -> Unit) {
        try {
            recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { text ->
                    val out = ArrayList<OcrLine>()
                    for (block in text.textBlocks) for (line in block.lines) {
                        val r = line.boundingBox ?: continue
                        out.add(OcrLine(line.text, r.top, r.bottom, r.left))
                    }
                    bmp.recycle()
                    done(out, "ok")
                }
                .addOnFailureListener { e ->
                    bmp.recycle()
                    done(null, "text recognition failed (" + e.javaClass.simpleName + ")")
                }
        } catch (e: Exception) {
            bmp.recycle()
            done(null, "text recognition not available (" + e.javaClass.simpleName + ")")
        }
    }
}
