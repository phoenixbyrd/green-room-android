package com.greenroom.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After a reboot, restart the 15-minute message poll if notifications are on. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && ctx.getSharedPreferences("gr_poll", Context.MODE_PRIVATE)
                        .getBoolean("notify_on", false)) {
            PollJobService.schedule(ctx);
        }
    }
}
