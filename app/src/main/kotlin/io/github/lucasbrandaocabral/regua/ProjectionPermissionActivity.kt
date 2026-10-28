package io.github.lucasbrandaocabral.regua

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * Tela invisível que só pede ao Android a permissão de gravar a tela (o pedido precisa vir de uma
 * Activity) e entrega o resultado para o [OverlayService]. Ao fechar, o jogo volta para a frente.
 */
class ProjectionPermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) startActivityForResult(captureIntent(this), REQUEST_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            OverlayService.startTracking(this, resultCode, data)
        } else {
            Toast.makeText(this, "Sem a gravação de tela a régua fica no modo manual.", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    companion object {
        private const val REQUEST_CAPTURE = 1

        /** Pedido de gravação da tela inteira: as coordenadas da régua são as da tela toda. */
        fun captureIntent(activity: Activity): Intent {
            val manager = activity.getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                manager.createScreenCaptureIntent()
            }
        }
    }
}
