package com.marat.albion.audio

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.marat.albion.AlbionApp
import com.marat.albion.MainActivity

/**
 * Фоновое воспроизведение «Аудио с текстом»: MediaSession над общим [ListeningPlayer].
 * Уведомление с кнопками и работу на заблокированном экране берёт на себя Media3.
 */
class ListeningService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = (application as AlbionApp).container.listening.player
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Смахнули приложение на паузе — служба не нужна; если играет, пусть доиграет. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            (application as AlbionApp).container.listening.stop()
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Плеер принадлежит приложению (им же пользуется экран), поэтому освобождаем только сессию.
        session?.release()
        session = null
        super.onDestroy()
    }
}
