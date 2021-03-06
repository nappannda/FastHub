package com.fastaccess.provider.tasks.notification;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;
import android.view.View;

import com.annimon.stream.Stream;
import com.fastaccess.R;
import com.fastaccess.data.dao.model.Comment;
import com.fastaccess.data.dao.model.Login;
import com.fastaccess.data.dao.model.Notification;
import com.fastaccess.helper.AppHelper;
import com.fastaccess.helper.InputHelper;
import com.fastaccess.helper.ParseDateFormat;
import com.fastaccess.helper.PrefGetter;
import com.fastaccess.helper.RxHelper;
import com.fastaccess.provider.rest.RestProvider;
import com.firebase.jobdispatcher.Constraint;
import com.firebase.jobdispatcher.FirebaseJobDispatcher;
import com.firebase.jobdispatcher.GooglePlayDriver;
import com.firebase.jobdispatcher.Job;
import com.firebase.jobdispatcher.JobParameters;
import com.firebase.jobdispatcher.JobService;
import com.firebase.jobdispatcher.Lifetime;
import com.firebase.jobdispatcher.RetryStrategy;
import com.firebase.jobdispatcher.Trigger;
import com.nostra13.universalimageloader.core.ImageLoader;
import com.nostra13.universalimageloader.core.assist.FailReason;
import com.nostra13.universalimageloader.core.listener.ImageLoadingListener;

import java.util.List;

import rx.Observable;
import rx.schedulers.Schedulers;

/**
 * Created by Kosh on 19 Feb 2017, 6:32 PM
 */

public class NotificationSchedulerJobTask extends JobService {
    private final static String JOB_ID = "fasthub_notification";

    private final static int THIRTY_MINUTES = 30 * 60;
    private static final String NOTIFICATION_GROUP_ID = "FastHub";

    @Override public boolean onStartJob(JobParameters job) {
        if (PrefGetter.getNotificationTaskDuration() == -1) {
            scheduleJob(this, -1, false);
            finishJob(job);
            return true;
        }
        Login login = null;
        try {
            login = Login.getUser();
        } catch (Exception ignored) {}
        if (login != null) {
            RestProvider.getNotificationService()
                    .getNotifications(ParseDateFormat.getLastWeekDate())
                    .subscribeOn(Schedulers.io())
                    .subscribe(item -> {
                        AppHelper.cancelAllNotifications(getApplicationContext());
                        if (item != null) {
                            onSave(item.getItems(), job);
                        } else {
                            finishJob(job);
                        }
                    }, throwable -> jobFinished(job, true));
        } else {
            finishJob(job);
        }
        return true;
    }

    @Override public boolean onStopJob(JobParameters jobParameters) {
        return false;
    }

    public static void scheduleJob(@NonNull Context context) {
        int duration = PrefGetter.getNotificationTaskDuration();
        scheduleJob(context, duration == 0 ? THIRTY_MINUTES : duration, false);
    }

    public static void scheduleJob(@NonNull Context context, int duration, boolean cancel) {
        FirebaseJobDispatcher dispatcher = new FirebaseJobDispatcher(new GooglePlayDriver(context));
        if (cancel) dispatcher.cancel(JOB_ID);
        if (duration == -1) {
            dispatcher.cancel(JOB_ID);
            return;
        }
        duration = duration <= 0 ? THIRTY_MINUTES : duration;
        Job.Builder builder = dispatcher
                .newJobBuilder()
                .setTag(JOB_ID)
                .setRetryStrategy(RetryStrategy.DEFAULT_LINEAR)
                .setLifetime(Lifetime.FOREVER)
                .setRecurring(true)
                .setConstraints(Constraint.ON_ANY_NETWORK)
                .setTrigger(Trigger.executionWindow(10, duration))
                .setService(NotificationSchedulerJobTask.class);
        dispatcher.mustSchedule(builder.build());
    }

    private void onSave(@Nullable List<Notification> notificationThreadModels, JobParameters job) {
        if (notificationThreadModels != null) {
            RxHelper.safeObservable(Notification.save(notificationThreadModels)).subscribe();
            onNotifyUser(notificationThreadModels, job);
        }
    }

    private void onNotifyUser(@NonNull List<Notification> notificationThreadModels, JobParameters job) {
        long count = Stream.of(notificationThreadModels)
                .filter(Notification::isUnread)
                .count();
        if (count == 0) {
            AppHelper.cancelAllNotifications(getApplicationContext());
            finishJob(job);
            return;
        }
        Context context = getApplicationContext();
        int accentColor = ContextCompat.getColor(this, R.color.material_blue_700);
        Bitmap largeIcon = BitmapFactory.decodeResource(context.getResources(),
                R.mipmap.ic_launcher);
        String[] url = new String[1];
        Notification first = notificationThreadModels.get(0);
        Observable.from(notificationThreadModels)
                .subscribeOn(Schedulers.io())
                .filter(notification -> notification.isUnread() && first.getId() != notification.getId())
                .limit(10)
                .flatMap(notification -> RestProvider.getNotificationService()
                        .getComment(notification.getSubject().getLatestCommentUrl())
                        .subscribeOn(Schedulers.io()), (thread, comment) -> {
                    url[0] = comment.getUser().getAvatarUrl();
                    if (!InputHelper.isEmpty(thread.getSubject().getLatestCommentUrl())) {
                        getNotificationWithComment(context, accentColor, thread, comment, url[0]);
                        return null;
                    }
                    showNotificationWithoutComment(context, accentColor, thread, url[0]);
                    return thread;
                })
                .subscribeOn(Schedulers.io())
                .subscribe(thread -> {/*do nothing in here*/}, throwable -> jobFinished(job, true), () -> {
                    android.app.Notification grouped = getSummaryGroupNotification(first, accentColor);
                    showNotification(first.getId(), grouped);
                    finishJob(job);
                });
    }

    private void finishJob(JobParameters job) {
        jobFinished(job, false);
    }

    private void showNotificationWithoutComment(Context context, int accentColor, Notification thread, String iconUrl) {
        ImageLoader.getInstance().loadImage(iconUrl, new ImageLoadingListener() {
            @Override
            public void onLoadingStarted(String s, View view) {

            }

            @Override
            public void onLoadingFailed(String s, View view, FailReason failReason) {
                android.app.Notification toAdd = getNotification(thread.getSubject().getTitle(), thread.getRepository().getFullName())
                        .setContentIntent(getPendingIntent(thread.getId(), thread.getSubject().getUrl()))
                        .addAction(R.drawable.ic_github, context.getString(R.string.open), getPendingIntent(thread.getId(), thread
                                .getSubject().getUrl()))
                        .addAction(R.drawable.ic_eye_off, context.getString(R.string.mark_as_read), getReadOnlyPendingIntent(thread.getId(), thread
                                .getSubject().getUrl()))
                        .setWhen(thread.getUpdatedAt() != null ? thread.getUpdatedAt().getTime() : System.currentTimeMillis())
                        .setShowWhen(true)
                        .setColor(accentColor)
                        .setGroup(NOTIFICATION_GROUP_ID)
                        .build();
                showNotification(thread.getId(), toAdd);
            }

            @Override
            public void onLoadingComplete(String s, View view, Bitmap bitmap) {
                android.app.Notification toAdd = getNotification(thread.getSubject().getTitle(), thread.getRepository().getFullName())
                        .setLargeIcon(bitmap)
                        .setContentIntent(getPendingIntent(thread.getId(), thread.getSubject().getUrl()))
                        .addAction(R.drawable.ic_github, context.getString(R.string.open), getPendingIntent(thread.getId(), thread
                                .getSubject().getUrl()))
                        .addAction(R.drawable.ic_eye_off, context.getString(R.string.mark_as_read), getReadOnlyPendingIntent(thread.getId(), thread
                                .getSubject().getUrl()))
                        .setWhen(thread.getUpdatedAt() != null ? thread.getUpdatedAt().getTime() : System.currentTimeMillis())
                        .setShowWhen(true)
                        .setColor(accentColor)
                        .setGroup(NOTIFICATION_GROUP_ID)
                        .build();
                showNotification(thread.getId(), toAdd);
            }

            @Override
            public void onLoadingCancelled(String s, View view) {

                android.app.Notification toAdd = getNotification(thread.getSubject().getTitle(), thread.getRepository().getFullName())
                        .setContentIntent(getPendingIntent(thread.getId(), thread.getSubject().getUrl()))
                        .addAction(R.drawable.ic_github, context.getString(R.string.open), getPendingIntent(thread.getId(), thread
                                .getSubject().getUrl()))
                        .addAction(R.drawable.ic_eye_off, context.getString(R.string.mark_as_read), getReadOnlyPendingIntent(thread.getId(), thread
                                .getSubject().getUrl()))
                        .setWhen(thread.getUpdatedAt() != null ? thread.getUpdatedAt().getTime() : System.currentTimeMillis())
                        .setShowWhen(true)
                        .setColor(accentColor)
                        .setGroup(NOTIFICATION_GROUP_ID)
                        .build();
                showNotification(thread.getId(), toAdd);

            }
        });
    }

    private void getNotificationWithComment(Context context, int accentColor,
                                            Notification thread, Comment comment, String url) {
        ImageLoader.getInstance().loadImage(url, new ImageLoadingListener() {
            @Override
            public void onLoadingStarted(String s, View view) {

            }

            @Override
            public void onLoadingFailed(String s, View view, FailReason failReason) {
                android.app.Notification toAdd = getNotification(comment.getUser() != null ? comment.getUser().getLogin() : "", comment.getBody())
                        .setSmallIcon(R.drawable.ic_notification)
                        .setStyle(new NotificationCompat.BigTextStyle()
                                .setBigContentTitle(comment.getUser() != null ? comment.getUser().getLogin() : "")
                                .bigText(comment.getBody()))
                        .setWhen(comment.getCreatedAt().getTime())
                        .setShowWhen(true)
                        .addAction(R.drawable.ic_github, context.getString(R.string.open), getPendingIntent(thread.getId(),
                                thread.getSubject().getUrl()))
                        .addAction(R.drawable.ic_eye_off, context.getString(R.string.mark_as_read), getReadOnlyPendingIntent(thread.getId(),
                                thread.getSubject().getUrl()))
                        .setContentIntent(getPendingIntent(thread.getId(), thread.getSubject().getUrl()))
                        .setColor(accentColor)
                        .setGroup(NOTIFICATION_GROUP_ID)
                        .build();
                showNotification(thread.getId(), toAdd);
            }

            @Override
            public void onLoadingComplete(String s, View view, Bitmap bitmap) {
                android.app.Notification toAdd = getNotification(comment.getUser() != null ? comment.getUser().getLogin() : "", comment.getBody())
                        .setLargeIcon(bitmap)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setStyle(new NotificationCompat.BigTextStyle()
                                .setBigContentTitle(comment.getUser() != null ? comment.getUser().getLogin() : "")
                                .bigText(comment.getBody()))
                        .setWhen(comment.getCreatedAt().getTime())
                        .setShowWhen(true)
                        .addAction(R.drawable.ic_github, context.getString(R.string.open), getPendingIntent(thread.getId(),
                                thread.getSubject().getUrl()))
                        .addAction(R.drawable.ic_eye_off, context.getString(R.string.mark_as_read), getReadOnlyPendingIntent(thread.getId(),
                                thread.getSubject().getUrl()))
                        .setContentIntent(getPendingIntent(thread.getId(), thread.getSubject().getUrl()))
                        .setColor(accentColor)
                        .setGroup(NOTIFICATION_GROUP_ID)
                        .build();
                showNotification(thread.getId(), toAdd);
            }

            @Override
            public void onLoadingCancelled(String s, View view) {
                android.app.Notification toAdd = getNotification(comment.getUser() != null ? comment.getUser().getLogin() : "", comment.getBody())
                        .setSmallIcon(R.drawable.ic_notification)
                        .setStyle(new NotificationCompat.BigTextStyle()
                                .setBigContentTitle(comment.getUser() != null ? comment.getUser().getLogin() : "")
                                .bigText(comment.getBody()))
                        .setWhen(comment.getCreatedAt().getTime())
                        .setShowWhen(true)
                        .addAction(R.drawable.ic_github, context.getString(R.string.open), getPendingIntent(thread.getId(),
                                thread.getSubject().getUrl()))
                        .addAction(R.drawable.ic_eye_off, context.getString(R.string.mark_as_read), getReadOnlyPendingIntent(thread.getId(),
                                thread.getSubject().getUrl()))
                        .setContentIntent(getPendingIntent(thread.getId(), thread.getSubject().getUrl()))
                        .setColor(accentColor)
                        .setGroup(NOTIFICATION_GROUP_ID)
                        .build();
                showNotification(thread.getId(), toAdd);
            }
        });
    }

    private android.app.Notification getSummaryGroupNotification(@NonNull Notification thread, int accentColor) {
        return getNotification(thread.getSubject().getTitle(), thread.getRepository().getFullName())
                .setDefaults(PrefGetter.isNotificationSoundEnabled() ? NotificationCompat.DEFAULT_ALL : 0)
                .setContentIntent(getPendingIntent(thread.getId(), thread.getSubject().getUrl()))
                .addAction(R.drawable.ic_github, getString(R.string.open), getPendingIntent(thread.getId(), thread
                        .getSubject().getUrl()))
                .addAction(R.drawable.ic_eye_off, getString(R.string.mark_as_read), getReadOnlyPendingIntent(thread.getId(), thread
                        .getSubject().getUrl()))
                .setWhen(thread.getUpdatedAt() != null ? thread.getUpdatedAt().getTime() : System.currentTimeMillis())
                .setShowWhen(true)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(accentColor)
                .setGroup(NOTIFICATION_GROUP_ID)
                .setGroupSummary(true)
                .build();
    }

    private NotificationCompat.Builder getNotification(@NonNull String title, @NonNull String message) {
        return new NotificationCompat.Builder(this)
                .setContentTitle(title)
                .setContentText(message)
                .setAutoCancel(true);
    }

    private void showNotification(long id, android.app.Notification notification) {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(InputHelper.getSafeIntId(id), notification);
    }

    private PendingIntent getReadOnlyPendingIntent(long id, @NonNull String url) {
        Intent intent = ReadNotificationService.start(getApplicationContext(), id, url, true);
        return PendingIntent.getService(getApplicationContext(), InputHelper.getSafeIntId(id) / 2, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent getPendingIntent(long id, @NonNull String url) {
        Intent intent = ReadNotificationService.start(getApplicationContext(), id, url);
        return PendingIntent.getService(getApplicationContext(), InputHelper.getSafeIntId(id), intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }
}