package com.okkikki.kkiri.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import android.widget.ImageView
import android.widget.LinearLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * QR 그리기 (D-25, 03_SCREENS CAR-02 ①). CAR-02 페이지와 CAR-05 "QR 다시 보기" 모달이 같이 쓴다.
 * - qr.url을 그대로 인코딩한다(바꾸거나 줄이지 않음).
 * - 흰 바탕·검은 모듈·여백 4칸. 화면이 어두워도 QR 영역은 흰 바탕으로 고정, 반전·색 입히기 없음.
 * - 크기는 창의 짧은 변 기준으로 잡아서 작은 창(화면 분할)에서도 충분히 크게 보인다.
 * - 만료 판단은 부르는 쪽(ScreenViews)이 한다. 만료면 이 함수를 부르지 않는다.
 */
object QrView {

    /** QR 그림을 담은 ImageView. 인코딩에 실패하면 null(부르는 쪽이 주소 글자만 보여 준다) */
    fun create(context: Context, url: String): ImageView? {
        val metrics = context.resources.displayMetrics
        val side = (minOf(metrics.widthPixels, metrics.heightPixels) * SIDE_RATIO).toInt()
            .coerceAtLeast(MIN_SIDE_PX)
        val bitmap = bitmap(url, side) ?: return null
        return ImageView(context).apply {
            setImageBitmap(bitmap)
            setBackgroundColor(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(side, side)
            contentDescription = url
        }
    }

    /** sidePx × sidePx 흑백 QR 비트맵 */
    fun bitmap(text: String, sidePx: Int): Bitmap? = try {
        val hints = mapOf(
            EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sidePx, sidePx, hints)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h) { i -> if (matrix.get(i % w, i / w)) Color.BLACK else Color.WHITE }
        Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    } catch (e: Exception) {
        Log.e(TAG, "QR 인코딩 실패", e)
        null
    }

    private const val TAG = "KKIRI-Qr"
    /** 여백(조용한 영역) 칸 수. D-25: 4칸 이상 */
    private const val QUIET_ZONE_MODULES = 4
    /** 창의 짧은 변에 대한 QR 한 변 비율 */
    private const val SIDE_RATIO = 0.35f
    private const val MIN_SIDE_PX = 320
}
