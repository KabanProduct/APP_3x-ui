package dev.threex.mobile.domain

enum class ConnectionPreset(val title: String, val description: String) {
    REALITY_VISION("VLESS · REALITY · Vision", "Прямое подключение через TCP/RAW. Проверка target, SNI и свободного порта."),
    XHTTP_REALITY("VLESS · XHTTP · REALITY", "Альтернативный транспорт. Требуется проверка поддержки сервером и ядром телефона."),
    XHTTP_TLS("VLESS · XHTTP · TLS", "Для своего домена с действующим сертификатом. Reverse proxy/CDN настраивается отдельно."),
}
