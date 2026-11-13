/*
 * fork: náhrada stejnojmenné třídy SDK, jejíž původní verze se při sestavení
 * z SDK vyřazuje (úloha linphoneSdkNoPush v app/build.gradle.kts). Push
 * notifikace aplikace nepoužívá, rozhraní zůstává shodné s SDK 5.5.13.
 */
package org.linphone.core.tools;

import android.content.Context;

public class PushNotificationUtils {
    public interface PushHelperInterface {
        void init(Context context);

        boolean isAvailable(Context context);
    }

    public static void init(Context context) {
        Log.i("[Push Utils] Push notifications are not used in this build");
    }

    public static boolean isAvailable(Context context) {
        return false;
    }
}
