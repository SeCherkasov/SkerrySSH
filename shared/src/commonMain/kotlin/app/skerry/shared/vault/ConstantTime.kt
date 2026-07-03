package app.skerry.shared.vault

/**
 * Сравнение секретных байтов за время, не зависящее от места первого расхождения (OR-аккумулятор
 * XOR-разниц, без ранних выходов) — по таймингу нельзя подбирать ключ побайтово. Расхождение длин
 * возвращает false сразу: длина ключей фиксирована форматом vault и секретом не является.
 */
internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}
