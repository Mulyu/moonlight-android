package com.limelight.background;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.limelight.Game;
import com.limelight.R;

/**
 * Keeps the process (and with it, the still-connected NvConnection, audio track, and input
 * channels) alive while the app is backgrounded with "keep streaming in background" enabled,
 * by holding a foreground-service notification. Modeled on XStreaming's own
 * StreamKeepAliveService: armed (started as a plain, non-foreground service) while the app is
 * still in the foreground -- right after a stream connects -- then promoted to a real foreground
 * service only once actually backgrounded, since Android disallows starting a *new* foreground
 * service from the background but not calling startForeground() on one already running.
 *
 * This service does no decoding itself; MediaCodecDecoderRenderer's pause/resume and
 * AndroidAudioRenderer/NvConnection already keep running independently of the Activity. Its only
 * job is keeping the process from being killed while there's no visible Activity, and giving the
 * user a way back in (tap) or out (Disconnect).
 */
public class StreamKeepAliveService extends Service {
    private static final String ACTION_PROMOTE = "com.limelight.background.PROMOTE";
    private static final String ACTION_DEMOTE = "com.limelight.background.DEMOTE";
    private static final String ACTION_DISCONNECT = "com.limelight.background.DISCONNECT";
    private static final String EXTRA_TITLE = "title";

    private static final String CHANNEL_ID = "stream_keepalive";
    private static final int NOTIFICATION_ID = 2;

    private static volatile Runnable disconnectListener;

    public static void arm(Context context) {
        context.startService(new Intent(context, StreamKeepAliveService.class));
    }

    public static void promote(Context context, String title) {
        Intent intent = new Intent(context, StreamKeepAliveService.class);
        intent.setAction(ACTION_PROMOTE);
        intent.putExtra(EXTRA_TITLE, title);
        context.startService(intent);
    }

    public static void demote(Context context) {
        Intent intent = new Intent(context, StreamKeepAliveService.class);
        intent.setAction(ACTION_DEMOTE);
        context.startService(intent);
    }

    public static void disarm(Context context) {
        context.stopService(new Intent(context, StreamKeepAliveService.class));
    }

    /** Called from the notification's Disconnect action; cleared once handled. */
    public static void setDisconnectListener(Runnable listener) {
        disconnectListener = listener;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_PROMOTE.equals(action)) {
            String title = intent.getStringExtra(EXTRA_TITLE);
            startForeground(NOTIFICATION_ID, buildNotification(title));
        }
        else if (ACTION_DEMOTE.equals(action)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        }
        else if (ACTION_DISCONNECT.equals(action)) {
            Runnable listener = disconnectListener;
            if (listener != null) {
                listener.run();
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            stopSelf();
        }

        // Arming (no action) just keeps the service alive with nothing shown yet.
        return START_NOT_STICKY;
    }

    private Notification buildNotification(String title) {
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.background_streaming_channel_name), NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }

        Intent contentIntent = new Intent(this, Game.class);
        contentIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent contentPendingIntent = PendingIntent.getActivity(this, 0, contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent disconnectIntent = new Intent(this, StreamKeepAliveService.class);
        disconnectIntent.setAction(ACTION_DISCONNECT);
        PendingIntent disconnectPendingIntent = PendingIntent.getService(this, 0, disconnectIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title != null ? title : getString(R.string.background_streaming_notification_title))
                .setContentText(getString(R.string.background_streaming_notification_text))
                .setSmallIcon(R.drawable.app_icon)
                .setContentIntent(contentPendingIntent)
                .addAction(0, getString(R.string.background_streaming_disconnect), disconnectPendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
