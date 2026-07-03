package app.skerry.server.db

import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll

/**
 * Watermark распространения tombstone'ов: минимум курсоров всех устройств аккаунта. Записи с
 * `serverSeq ≤ watermark` дочитаны КАЖДЫМ устройством, и только их безопасно компактить/чистить —
 * иначе отставшее устройство воскресило бы запись на следующем pull. Устройство без курсора
 * (null/никогда не синкалось) тянет watermark к 0 → ничего не трогаем; аккаунт без устройств →
 * watermark = MAX → можно всё (воскрешать некому). Общее ядро для
 * [RecordRepository.compactedTombstoneIds] и [AdminRepository.purgeTombstones].
 * Вызывать только внутри открытой транзакции.
 */
internal fun tombstoneWatermark(accountId: String): Long {
    val cursors = Devices.selectAll()
        .where { Devices.accountId eq accountId }
        .map { it[Devices.lastSyncVersion] ?: 0L }
    return if (cursors.isEmpty()) Long.MAX_VALUE else cursors.min()
}

/** Предикат «надгробие аккаунта, уже распространённое на все устройства» (deleted ∧ serverSeq ≤ watermark). */
internal fun propagatedTombstones(accountId: String, watermark: Long): Op<Boolean> =
    (Records.accountId eq accountId) and (Records.deleted eq true) and (Records.serverSeq lessEq watermark)
