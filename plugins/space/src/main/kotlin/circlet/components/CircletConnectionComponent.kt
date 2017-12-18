package circlet.components

import circlet.client.*
import circlet.client.api.*
import circlet.client.api.transport.*
import circlet.utils.*
import com.intellij.concurrency.*
import com.intellij.notification.*
import com.intellij.notification.Notification
import com.intellij.openapi.components.*
import com.intellij.openapi.project.*
import com.intellij.xml.util.*
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.jetty.*
import runtime.async.*
import runtime.reactive.*
import java.awt.*
import java.net.*
import java.util.concurrent.*

class CircletConnectionComponent(private val project: Project) :
    AbstractProjectComponent(project),
    ILifetimedComponent by LifetimedComponent(project) {

    private val endpoint = "http://localhost:8000"

    private val loginDataComponent = component<CircletLoginComponent>()

    private val loginModel = LoginModel(
        IdeaPersistence, endpoint, ApiScheme(emptyArray()) /*TODO*/, EmptyLoggedStateWatcher,
        { authCheckFailedNotification() }, NotificationSettingKind.Ide
    ) {
        // TODO: NOTIFY
    }

    init {
        loginDataComponent.enabled.whenTrue(componentLifetime) { enabledLt ->
            JobScheduler.getScheduler().schedule(
                {
                    async {
                        if (IdeaPersistence.get("token") ?: "" == "") {
                            authCheckFailedNotification()
                        }
                        else {
                            try {
                                KCircletClient.start(loginModel, endpoint, enabledLt, true)
                                KCircletClient.connection.status.view(enabledLt) { stateLt, state ->
                                    when (state) {
                                        ConnectionStatus.CONNECTED -> {
                                            notifyConnected()
                                        }
                                        ConnectionStatus.CONNECTING -> {
                                            notifyReconnect(stateLt)
                                        }
                                        else -> {
                                        }
                                    }
                                }
                            }
                            catch (th: Throwable) {
                                authCheckFailedNotification()
                            }
                        }
                    }
                }, 100, TimeUnit.MILLISECONDS)
        }


        loginDataComponent.enabled.whenFalse(componentLifetime) {
            notifyDisconnected(it)
        }

    }

    fun enable() {
        loginDataComponent.enabled.value = true
    }

    fun disable() {
        loginDataComponent.enabled.value = false
    }

    private fun notifyReconnect(lt: Lifetime) {
        val notification = Notification(
            "IdePLuginClient.notifyReconnect",
            "Circlet",
            XmlStringUtil.wrapInHtml("Failed to establish server connection. Will keep trying to reconnect.<br> <a href=\"update\">Switch off</a>"),
            NotificationType.INFORMATION,
            { _, _ -> disable() })
        notification.notify(lt, project)
    }

    private fun notifyDisconnected(lt: Lifetime) {
        val notification = Notification(
            "IdePLuginClient.notifyDisconnected",
            "Circlet",
            XmlStringUtil.wrapInHtml("Integration switched off.<br> <a href=\"update\">Switch on</a>"),
            NotificationType.INFORMATION,
            { _, _ -> enable() })
        notification.notify(lt, project)
    }

    private fun notifyConnected() {
        val notification = Notification(
            "IdePLuginClient.notifyDisconnected",
            "Circlet",
            XmlStringUtil.wrapInHtml("Signed in"),
            NotificationType.INFORMATION,
            { _, _ -> enable() })
        notification.notify(project)
    }

    private fun authCheckFailedNotification() {
        Notification(
            "IdePLuginClient.authCheckFailedNotification",
            "Circlet",
            XmlStringUtil.wrapInHtml("Not authenticated.<br> <a href=\"update\">Sign in</a>"),
            NotificationType.INFORMATION,
            { _, _ -> authenticate() })
            .notify(project)
    }

    private val seq = SequentialLifetimes(componentLifetime)

    fun authenticate() {
        val lt = seq.next()
        val server = embeddedServer(Jetty, 8080) {
            routing {
                get("auth") {
                    val token = call.parameters[TOKEN_PARAMETER]!!

                    loginModel.signIn(token, "")

                    call.respondText(
                        "Authorization successful! Now you can close this page and return to the IDE.",
                        ContentType.Text.Html
                    )

                    lt.terminate()
                }
            }
        }.start(wait = false)

        lt.add {
            server.stop(100, 5000, TimeUnit.MILLISECONDS)
        }

        Desktop.getDesktop().browse(URI(
            Navigator.login("http://localhost:8080/auth").absoluteHref(endpoint)
        ))

        // TODO:
    }
}
