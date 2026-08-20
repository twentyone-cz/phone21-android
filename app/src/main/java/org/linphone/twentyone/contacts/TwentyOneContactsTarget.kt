/*
 * fork: kam se ukládají nové kontakty — do adresáře telefonu, nebo do
 * adresáře synchronizační aplikace (pak je vidí i miniserver a další
 * zařízení). Volba žije v prefs, detekce adresářů jde přes ContactsContract
 * (bez oprávnění GET_ACCOUNTS).
 */
package org.linphone.twentyone.contacts

import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import org.linphone.twentyone.TwentyOneDiag

object TwentyOneContactsTarget {

    /** Adresář v telefonu (účet, do kterého se dají zapisovat kontakty). */
    data class Book(val type: String, val name: String)

    enum class Mode { AUTO, LOCAL, BOOK }

    private const val PREFS = "twentyone_contacts"
    private const val KEY_MODE = "target_mode"
    private const val KEY_TYPE = "target_type"
    private const val KEY_NAME = "target_name"
    private const val KEY_LOST = "target_lost_hinted"

    /** Známá synchronizační aplikace: hlavní účet a adresáře pod ním. */
    const val SYNC_APP = "at.bitfire.davdroid"
    private const val SYNC_BOOK_SUFFIX = ".address_book"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun davAppInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(SYNC_APP, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Adresáře, do kterých jde zapisovat. Bere je ze dvou zdrojů: řádků
     * ContactsContract.Settings (zakládá je synchronizační aplikace)
     * a z účtů, pod kterými už nějaké kontakty leží.
     */
    fun availableBooks(context: Context): List<Book> {
        val out = LinkedHashSet<Book>()
        if (!TwentyOneContacts.hasReadPermission(context)) return emptyList()
        try {
            context.contentResolver.query(
                ContactsContract.Settings.CONTENT_URI,
                arrayOf(
                    ContactsContract.Settings.ACCOUNT_TYPE,
                    ContactsContract.Settings.ACCOUNT_NAME
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val type = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (isBook(type)) out.add(Book(type, name))
                }
            }
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.RawContacts.ACCOUNT_TYPE,
                    ContactsContract.RawContacts.ACCOUNT_NAME
                ),
                "${ContactsContract.RawContacts.DELETED} = 0", null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val type = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (isBook(type)) out.add(Book(type, name))
                }
            }
        } catch (e: Exception) {
            TwentyOneDiag.log("P21-CFG", "adresáře kontaktů nejde přečíst")
        }
        return out.toList()
    }

    /** Hlavní účet synchronizační aplikace kontakty nedrží — ty jsou v adresářích. */
    private fun isBook(type: String): Boolean =
        type.isNotEmpty() && type != SYNC_APP && !type.startsWith("com.android.contacts")

    fun mode(context: Context): Mode = try {
        Mode.valueOf(prefs(context).getString(KEY_MODE, Mode.AUTO.name) ?: Mode.AUTO.name)
    } catch (e: IllegalArgumentException) {
        Mode.AUTO
    }

    fun setLocal(context: Context) {
        prefs(context).edit().putString(KEY_MODE, Mode.LOCAL.name)
            .remove(KEY_TYPE).remove(KEY_NAME).remove(KEY_LOST).apply()
    }

    fun setAuto(context: Context) {
        prefs(context).edit().putString(KEY_MODE, Mode.AUTO.name)
            .remove(KEY_TYPE).remove(KEY_NAME).remove(KEY_LOST).apply()
    }

    fun setBook(context: Context, book: Book) {
        prefs(context).edit().putString(KEY_MODE, Mode.BOOK.name)
            .putString(KEY_TYPE, book.type).putString(KEY_NAME, book.name)
            .remove(KEY_LOST).apply()
    }

    /** Adresář pro zápis, nebo null = uložit jen do telefonu. */
    fun resolve(context: Context): Book? {
        val books = availableBooks(context)
        return when (mode(context)) {
            Mode.LOCAL -> null
            Mode.BOOK -> {
                val p = prefs(context)
                val want = Book(
                    p.getString(KEY_TYPE, "").orEmpty(),
                    p.getString(KEY_NAME, "").orEmpty()
                )
                if (books.contains(want)) {
                    want
                } else {
                    if (!p.getBoolean(KEY_LOST, false)) {
                        p.edit().putBoolean(KEY_LOST, true).apply()
                        TwentyOneDiag.log(
                            "P21-CFG",
                            "zvolený adresář kontaktů zmizel — ukládá se do telefonu"
                        )
                    }
                    null
                }
            }
            Mode.AUTO -> if (books.size == 1) books[0] else null
        }
    }

    /** Text pro obrazovku Konexe. */
    fun describe(context: Context): String {
        val target = resolve(context)
        return when {
            target != null && mode(context) == Mode.AUTO ->
                context.getString(
                    org.linphone.R.string.twentyone_contacts_target_auto_suffix,
                    target.name
                )
            target != null -> target.name
            else -> context.getString(org.linphone.R.string.twentyone_contacts_target_phone)
        }
    }
}
