package io.tolgee.service.notification

import io.sentry.Sentry
import io.tolgee.component.FrontendUrlProvider
import io.tolgee.component.email.TolgeeEmailSender
import io.tolgee.constants.NotificationType
import io.tolgee.dtos.misc.EmailParams
import io.tolgee.model.Notification
import io.tolgee.model.UserAccount
import io.tolgee.model.enums.TaskType
import io.tolgee.util.Logging
import io.tolgee.util.logger
import org.springframework.stereotype.Component

@Component
class EmailNotificationsService(
  private val tolgeeEmailSender: TolgeeEmailSender,
  private val frontendUrlProvider: FrontendUrlProvider,
) : Logging {
  fun sendEmailNotification(notification: Notification) {
    val params = composeEmailParams(notification)

    try {
      params?.let { tolgeeEmailSender.sendEmail(it) } // TODO remove nullable
    } catch (e: Exception) {
      logger.error(e.message)
      Sentry.captureException(e)
    }
  }

  private fun composeEmailParams(notification: Notification) =
    when (notification.type) {
      NotificationType.TASK_ASSIGNED -> {
        val task =
          notification.linkedTask
            ?: throw IllegalStateException(
              "Notification of type ${notification.type} must contain linkedTask.",
            )
        val taskUrl = getTaskUrl(task.project.id, task.number)
        val myTasksUrl = getMyTasksUrl()

        val subject = "New task assignment"
        val text =
          """
          You've been assigned to a task:<br/>
          <a href="$taskUrl">${task.name} #${task.number} (${task.language.name}) - ${getTaskTypeName(task.type)}</a>
          <br/><br/>
          Check all your tasks <a href="$myTasksUrl">here</a>.
          """.trimIndent()
        composeEmailParams(notification.user, subject, text)
      }

      else -> null // TODO add branches for all types, remove else
    }

  private fun composeEmailParams(
    user: UserAccount,
    subject: String,
    text: String,
  ) = EmailParams(
    to = user.username,
    subject = subject,
    text =
      """
      Hello!
      👋<br/><br/>          
      $text
      <br/><br/>
      Regards,<br/>
      Tolgee
      """.trimIndent(),
  )

  private fun getTaskTypeName(type: TaskType): String =
    if (type === TaskType.TRANSLATE) {
      "translate"
    } else {
      "review"
    }

  fun getTaskUrl(
    projectId: Long,
    taskId: Long,
  ): String = "${frontendUrlProvider.url}/projects/$projectId/task?number=$taskId&detail=true"

  fun getMyTasksUrl(): String = "${frontendUrlProvider.url}/my-tasks"
}
