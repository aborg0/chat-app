package com.example.auth

import com.example.api.{AuthResponse, BackendClient}
import com.raquo.laminar.api.L.*
import org.scalajs.dom
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

object LoginView {
  def view(onAuthenticated: AuthResponse => Unit): HtmlElement = {
    val usernameVar = Var("")
    val passwordVar = Var("")
    val socialProviderVar = Var("github")
    val socialUserIdVar = Var("")
    val socialDisplayNameVar = Var("")
    val messageVar = Var("")

    val deviceId = stableDeviceId()

    def setMessage(value: String): Unit = {
      messageVar.set(value)
    }

    def handleRegister(): Unit = {
      val username = usernameVar.now().trim
      val password = passwordVar.now()
      if username.isEmpty || password.isEmpty then {
        setMessage("Username and password are required.")
      } else {
        setMessage("Registering...")
        BackendClient.register(username, password).foreach {
          case Right(response) =>
            setMessage(s"Registered user with id ${response.userId}. You can login now.")
          case Left(error) =>
            setMessage(s"Registration failed: $error")
        }
      }
    }

    def handlePasswordLogin(): Unit = {
      val username = usernameVar.now().trim
      val password = passwordVar.now()
      if username.isEmpty || password.isEmpty then {
        setMessage("Username and password are required.")
      } else {
        setMessage("Logging in...")
        BackendClient.login(username, password, deviceId).foreach {
          case Right(auth) =>
            setMessage("")
            onAuthenticated(auth)
          case Left(error) =>
            setMessage(s"Login failed: $error")
        }
      }
    }

    def handleSocialLogin(): Unit = {
      val provider = socialProviderVar.now().trim
      val providerUserId = socialUserIdVar.now().trim
      val displayName = Option(socialDisplayNameVar.now().trim).filter(_.nonEmpty)
      if provider.isEmpty || providerUserId.isEmpty then {
        setMessage("Provider and provider user id are required for social login.")
      } else {
        setMessage("Logging in with social provider...")
        BackendClient.socialLogin(provider, providerUserId, displayName, deviceId).foreach {
          case Right(auth) =>
            setMessage("")
            onAuthenticated(auth)
          case Left(error) =>
            setMessage(s"Social login failed: $error")
        }
      }
    }

    div(
      cls := "login-screen",
      div(
        cls := "login-card",
        h2("Welcome To Chatty"),
        p("Sign in to your workspace. Backend URL can be overridden with localStorage key 'backendBaseUrl'."),
      div(
        cls := "login-section",
        h3("Username / Password"),
        input(
          typ("text"),
          placeholder := "Username",
          onInput.mapToValue --> usernameVar
        ),
        input(
          typ("password"),
          placeholder := "Password",
          onInput.mapToValue --> passwordVar
        ),
        button("Register", onClick.mapTo(()) --> (_ => handleRegister())),
        button("Login", onClick.mapTo(()) --> (_ => handlePasswordLogin()))
      ),
      div(
        cls := "login-section",
        h3("Social Login"),
        input(
          typ("text"),
          placeholder := "Provider (e.g. github)",
          value := "github",
          onInput.mapToValue --> socialProviderVar
        ),
        input(
          typ("text"),
          placeholder := "Provider user id",
          onInput.mapToValue --> socialUserIdVar
        ),
        input(
          typ("text"),
          placeholder := "Display name (optional)",
          onInput.mapToValue --> socialDisplayNameVar
        ),
        button("Social Login", onClick.mapTo(()) --> (_ => handleSocialLogin()))
      ),
      div(
        cls := "login-device",
        b("Device id: "),
        code(deviceId)
      ),
      p(cls := "login-message", child.text <-- messageVar.signal)
      )
    )
  }

  private def stableDeviceId(): String = {
    val key = "chat-device-id"
    val existing = Option(dom.window.localStorage.getItem(key)).filter(_.nonEmpty)
    existing.getOrElse {
      val generated = generateBrowserDeviceId()
      dom.window.localStorage.setItem(key, generated)
      generated
    }
  }

  private def generateBrowserDeviceId(): String = {
    try {
      val crypto = js.Dynamic.global.selectDynamic("crypto")
      if !js.isUndefined(crypto) && crypto != null then {
        val randomUuid = try {
          crypto.selectDynamic("randomUUID")
        } catch { case _ => js.undefined }
        if !js.isUndefined(randomUuid) && randomUuid != null then {
          try {
            val uuid = randomUuid.asInstanceOf[js.Function0[js.Any]].apply()
            return uuid.asInstanceOf[String]
          } catch { case _ => () }
        }
      }
    } catch { case _ => () }
    
    // Fallback to timestamp-based device ID
    val ts = js.Date.now().toLong
    val rnd = (math.random() * 1_000_000_000L).toLong
    s"web-$ts-$rnd"
  }
}