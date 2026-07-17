package app.skerry.server.mail

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Email template factory: generates HTML + plain-text bodies in zh/en/ru.
 * Each template receives [brand] (app name, subjects from JSON) and the event data.
 *
 * The HTML layout is intentionally minimal ("transparent"): no background color, dark text on
 * whatever the mail client renders, a thin divider under the title and above the footer. The
 * footer shows the sending server's public URL when configured.
 *
 * Language detection: uses a simple heuristic — if the email address portion before "@"
 * looks like CJK, returns "zh"; Cyrillic → "ru"; else "en".
 */
object EmailTemplates {

    /** Build a welcome email for new account registration. */
    fun welcome(cfg: MailBrandConfig, lang: String, serverUrl: String = ""): Pair<String, String> {
        val app = cfg.appName(lang)
        val subject = cfg.subject("welcome", lang)
        val html = when (lang) {
            "ru" -> welcomeRu(app, serverUrl)
            "zh" -> welcomeZh(app, serverUrl)
            else -> welcomeEn(app, serverUrl)
        }
        return subject to html
    }

    /** Build a new-device-pairing alert email. */
    fun newDevice(cfg: MailBrandConfig, lang: String, deviceName: String, platform: String?, time: ZonedDateTime, serverUrl: String = ""): Pair<String, String> {
        val app = cfg.appName(lang)
        val subject = cfg.subject("new_device", lang)
        val plat = platform ?: ""
        val ts = time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"))
        val html = when (lang) {
            "ru" -> newDeviceRu(app, deviceName, plat, ts, serverUrl)
            "zh" -> newDeviceZh(app, deviceName, plat, ts, serverUrl)
            else -> newDeviceEn(app, deviceName, plat, ts, serverUrl)
        }
        return subject to html
    }

    /** Build a master-password change notification. */
    fun passwordChanged(cfg: MailBrandConfig, lang: String, deviceName: String, time: ZonedDateTime, serverUrl: String = ""): Pair<String, String> {
        val app = cfg.appName(lang)
        val subject = cfg.subject("pw_changed", lang)
        val ts = time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"))
        val html = when (lang) {
            "ru" -> pwChangedRu(app, deviceName, ts, serverUrl)
            "zh" -> pwChangedZh(app, deviceName, ts, serverUrl)
            else -> pwChangedEn(app, deviceName, ts, serverUrl)
        }
        return subject to html
    }

    /** Build a suspicious-login alert (new IP). */
    fun suspiciousLogin(cfg: MailBrandConfig, lang: String, deviceName: String, ip: String, time: ZonedDateTime, serverUrl: String = ""): Pair<String, String> {
        val app = cfg.appName(lang)
        val subject = cfg.subject("suspicious_login", lang)
        val ts = time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"))
        val html = when (lang) {
            "ru" -> suspiciousRu(app, deviceName, ip, ts, serverUrl)
            "zh" -> suspiciousZh(app, deviceName, ip, ts, serverUrl)
            else -> suspiciousEn(app, deviceName, ip, ts, serverUrl)
        }
        return subject to html
    }

    // ---- Language detection ----

    fun detectLang(email: String, fallback: String = "en"): String {
        val local = email.substringBefore("@")
        return when {
            local.any { it in '一'..'鿿' } -> "zh"
            local.any { it in 'Ѐ'..'ӿ' } -> "ru"
            else -> fallback
        }
    }

    // ---- Shared layout ----

    /** Footer block: thin divider + small gray line with the server URL (when set). */
    private fun footer(serverUrl: String, lang: String): String {
        val auto = when (lang) {
            "ru" -> "Автоматическое сообщение"
            "zh" -> "此邮件为自动发送，请勿回复"
            else -> "Automated message — please do not reply"
        }
        val urlLine = if (serverUrl.isBlank()) "" else
            """<br><a href="$serverUrl" style="color:#888;text-decoration:none">$serverUrl</a>"""
        return """<p style="font-size:12px;color:#999;margin:32px 0 0;padding-top:14px;border-top:1px solid #e5e5e5">$auto$urlLine</p>"""
    }

    private fun page(title: String, body: String, serverUrl: String, lang: String): String = """
<!DOCTYPE html><html><body style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;max-width:560px;margin:0 auto;padding:24px 20px;color:#1a1a1a;line-height:1.65">
<h1 style="font-size:19px;font-weight:600;margin:0 0 4px;padding-bottom:14px;border-bottom:1px solid #e5e5e5">$title</h1>
$body
${footer(serverUrl, lang)}
</body></html>""".trimIndent()

    private fun p(html: String) = """<p style="font-size:15px;margin:16px 0">$html</p>"""

    private fun warn(html: String) =
        """<p style="font-size:14px;margin:16px 0;padding:12px 14px;border-left:3px solid #d97706;background:#fffbf5">$html</p>"""

    private fun info(html: String) =
        """<p style="font-size:14px;margin:16px 0;padding:12px 14px;border-left:3px solid #0ea365;background:#f6fdf9">$html</p>"""

    // --- Welcome ---

    private fun welcomeZh(app: String, serverUrl: String) = page(
        "欢迎加入 $app",
        p("你的账户 <strong>{{email}}</strong> 已创建成功。") +
        p("$app 采用端到端加密——你的密码永远不会离开设备，服务器不保存任何明文。主密码是你加密和解密保险库的<strong>唯一密钥</strong>，请务必妥善保管。") +
        info("🔐 下一步：下载客户端并设置主密码，开始管理你的连接。"),
        serverUrl, "zh",
    )

    private fun welcomeEn(app: String, serverUrl: String) = page(
        "Welcome to $app",
        p("Your account <strong>{{email}}</strong> has been created.") +
        p("$app uses end-to-end encryption — your password never leaves your device and the server stores no plaintext. Your master password is the <strong>only key</strong> to your vault — guard it carefully.") +
        info("🔐 Next: download the client and set your master password to start managing connections."),
        serverUrl, "en",
    )

    private fun welcomeRu(app: String, serverUrl: String) = page(
        "Добро пожаловать в $app",
        p("Ваша учётная запись <strong>{{email}}</strong> создана.") +
        p("$app использует сквозное шифрование — ваш пароль никогда не покидает устройство, сервер не хранит открытые данные. Мастер-пароль — <strong>единственный ключ</strong> к хранилищу, берегите его.") +
        info("🔐 Следующий шаг: скачайте клиент и задайте мастер-пароль для управления подключениями."),
        serverUrl, "ru",
    )

    // --- New device ---

    private fun newDeviceZh(app: String, name: String, platform: String, time: String, serverUrl: String) = page(
        "新设备已关联",
        p("设备 <strong>$name</strong>（$platform）于 $time 关联了你的 $app 保险库。") +
        warn("<strong>⚠️ 如非本人操作</strong>，请立即登录管理控制台撤销该设备。"),
        serverUrl, "zh",
    )

    private fun newDeviceEn(app: String, name: String, platform: String, time: String, serverUrl: String) = page(
        "New device linked",
        p("Device <strong>$name</strong> ($platform) linked to your $app vault at $time.") +
        warn("<strong>⚠️ If this wasn't you</strong>, sign into the admin console immediately and revoke this device."),
        serverUrl, "en",
    )

    private fun newDeviceRu(app: String, name: String, platform: String, time: String, serverUrl: String) = page(
        "Новое устройство",
        p("Устройство <strong>$name</strong> ($platform) подключилось к хранилищу $app — $time.") +
        warn("<strong>⚠️ Если это не вы</strong>, войдите в консоль администратора и отзовите устройство."),
        serverUrl, "ru",
    )

    // --- Password changed ---

    private fun pwChangedZh(app: String, name: String, time: String, serverUrl: String) = page(
        "主密码已变更",
        p("你的 $app 主密码已由设备 <strong>$name</strong> 于 $time 变更。") +
        info("如非本人操作，请联系管理员。"),
        serverUrl, "zh",
    )

    private fun pwChangedEn(app: String, name: String, time: String, serverUrl: String) = page(
        "Master password changed",
        p("Your $app master password was changed by device <strong>$name</strong> at $time.") +
        info("If this wasn't you, contact your administrator."),
        serverUrl, "en",
    )

    private fun pwChangedRu(app: String, name: String, time: String, serverUrl: String) = page(
        "Пароль изменён",
        p("Мастер-пароль $app изменён устройством <strong>$name</strong> — $time.") +
        info("Если это не вы, свяжитесь с администратором."),
        serverUrl, "ru",
    )

    // --- Suspicious login ---

    private fun suspiciousZh(app: String, name: String, ip: String, time: String, serverUrl: String) = page(
        "可疑登录提醒",
        p("你的 $app 账户于 $time 从新 IP <strong>$ip</strong> 登录，设备「$name」。") +
        warn("<strong>⚠️ 如非本人操作</strong>，请立即更改主密码并撤销该设备。"),
        serverUrl, "zh",
    )

    private fun suspiciousEn(app: String, name: String, ip: String, time: String, serverUrl: String) = page(
        "Suspicious sign-in",
        p("Your $app account was signed into from a new IP <strong>$ip</strong> at $time — device \"$name\".") +
        warn("<strong>⚠️ If this wasn't you</strong>, change your master password immediately and revoke the device."),
        serverUrl, "en",
    )

    private fun suspiciousRu(app: String, name: String, ip: String, time: String, serverUrl: String) = page(
        "Подозрительный вход",
        p("Вход в $app с нового IP <strong>$ip</strong> — $time, устройство «$name».") +
        warn("<strong>⚠️ Если это не вы</strong>, немедленно смените мастер-пароль и отзовите устройство."),
        serverUrl, "ru",
    )
}
