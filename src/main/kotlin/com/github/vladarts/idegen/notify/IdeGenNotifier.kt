package com.github.vladarts.idegen.notify

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

object ProjectGeneratorNotifier {

    private const val GROUP = "Project Generator"

    fun info(project: Project, content: String) = notify(project, content, NotificationType.INFORMATION)

    fun warning(project: Project, content: String) = notify(project, content, NotificationType.WARNING)

    fun error(project: Project, content: String) = notify(project, content, NotificationType.ERROR)

    private fun notify(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP)
            .createNotification(content, type)
            .notify(project)
    }
}
