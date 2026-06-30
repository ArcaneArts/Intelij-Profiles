package art.arcane.profiles

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType

/** Single entry point for the plugin's user-facing balloons (the "Profiles" notification group). */
object Notifications {

    fun info(content: String) = show(content, NotificationType.INFORMATION)

    fun warn(content: String) = show(content, NotificationType.WARNING)

    fun show(content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Profiles")
            .createNotification(content, type)
            .notify(null)
    }
}
