/*
 * fork: zápis kontaktů do systémového adresáře (ContactsContract).
 * Aplikace systémové kontakty už čte (ContactLoader) — auto (Bluetooth PBAP)
 * i systémové Kontakty ale vidí jen systémový adresář, proto se kontakty
 * ukládají tam. Bez zvoleného adresáře vzniká lokální RAW kontakt bez účtu
 * (ACCOUNT_TYPE = null); s adresářem se zapisuje pod něj, takže ho převezme
 * synchronizace. Při selhání zápisu vrací null a volající MUSÍ kontakt
 * ponechat v aplikační databázi.
 */
package org.linphone.twentyone.contacts

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.net.toUri
import org.linphone.twentyone.TwentyOneDiag
import org.linphone.utils.PhoneNumberUtils

object TwentyOneContacts {

    data class PhoneRow(val number: String, val vcardLabel: String?)

    fun hasWritePermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun hasReadPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /** Vloží kontakt; vrací id (informativní), null JEN při skutečném selhání zápisu. */
    fun insert(
        context: Context,
        firstName: String,
        lastName: String,
        displayName: String,
        organization: String,
        jobTitle: String,
        phones: List<PhoneRow>,
        sipAddresses: List<String>,
        photo: ByteArray? = null,
        starred: Boolean = false,
        book: TwentyOneContactsTarget.Book? = null
    ): String? {
        if (!hasWritePermission(context)) return null
        return try {
            val ops = arrayListOf<ContentProviderOperation>()
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, book?.type)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, book?.name)
                    .withValue(ContactsContract.RawContacts.STARRED, if (starred) 1 else 0)
                    .build()
            )
            ops.add(
                data(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, firstName)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, lastName)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, displayName)
                    .build()
            )
            if (organization.isNotEmpty() || jobTitle.isNotEmpty()) {
                ops.add(
                    data(ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, organization)
                        .withValue(ContactsContract.CommonDataKinds.Organization.TITLE, jobTitle)
                        .build()
                )
            }
            for (phone in phones) {
                // editor v aplikaci štítky nenastavuje — bez štítku je číslo
                // „mobil", ne bezejmenný „vlastní" typ
                val type = if (phone.vcardLabel.isNullOrEmpty()) {
                    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                } else {
                    PhoneNumberUtils.labelToType(phone.vcardLabel)
                }
                val op = data(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone.number)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, type)
                if (type == ContactsContract.CommonDataKinds.BaseTypes.TYPE_CUSTOM &&
                    !phone.vcardLabel.isNullOrEmpty()
                ) {
                    op.withValue(ContactsContract.CommonDataKinds.Phone.LABEL, phone.vcardLabel)
                }
                ops.add(op.build())
            }
            for (sip in sipAddresses) {
                ops.add(
                    data(ContactsContract.CommonDataKinds.SipAddress.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.SipAddress.SIP_ADDRESS, sip)
                        .withValue(
                            ContactsContract.CommonDataKinds.SipAddress.TYPE,
                            ContactsContract.CommonDataKinds.SipAddress.TYPE_OTHER
                        )
                        .build()
                )
            }
            if (photo != null) {
                ops.add(
                    data(ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, photo)
                        .build()
                )
            }

            val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            val rawUri = results.firstOrNull()?.uri
            if (rawUri == null) {
                TwentyOneDiag.log("P21-CFG", "systém kontakt nepřijal (uri=null)")
                return null
            }
            // zápis USPĚL — selhání následného čtení id (Contact Scopes umí
            // pustit zápis a odmítnout čtení) nesmí vyrobit falešný neúspěch,
            // volající by kontakt uložil podruhé lokálně
            try {
                contactIdForRawContact(context, rawUri) ?: rawUri.toString()
            } catch (_: Exception) {
                rawUri.toString()
            }
        } catch (e: Exception) {
            // GrapheneOS Contact Scopes: oprávnění se tváří udělené, zápis přesto selže
            TwentyOneDiag.log(
                "P21-CFG",
                "zápis kontaktu do systému selhal: ${e.javaClass.simpleName}"
            )
            null
        }
    }

    /** Smaže kontakt ze systému podle lookup URI (Friend.nativeUri). */
    fun delete(context: Context, lookupUriString: String): Boolean {
        if (!hasWritePermission(context)) return false
        return try {
            context.contentResolver.delete(lookupUriString.toUri(), null, null) > 0
        } catch (e: Exception) {
            TwentyOneDiag.log(
                "P21-CFG",
                "smazání kontaktu ze systému selhalo: ${e.javaClass.simpleName}"
            )
            false
        }
    }

    /** Duplicitní kontrola pro migraci: stejné číslo + stejné jméno už v systému je. */
    fun existsByNumberAndName(context: Context, number: String, displayName: String): Boolean {
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(0).equals(displayName, ignoreCase = true)) return true
                }
                false
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    /** Fotka kontaktu jako JPEG bajty pro Photo řádek; null = bez fotky.
     *  Zmenšuje na max 1080 px (binder limit ~1 MB na operaci). */
    fun photoBytes(context: Context, pathOrUri: String?): ByteArray? {
        if (pathOrUri.isNullOrEmpty()) return null
        return try {
            val stream = if (pathOrUri.startsWith("content:")) {
                context.contentResolver.openInputStream(pathOrUri.toUri())
            } else {
                java.io.FileInputStream(pathOrUri.removePrefix("file://").removePrefix("file:"))
            } ?: return null
            val bitmap = stream.use { android.graphics.BitmapFactory.decodeStream(it) }
                ?: return null
            val longest = maxOf(bitmap.width, bitmap.height)
            val scaled = if (longest > 1080) {
                val ratio = 1080f / longest
                android.graphics.Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt().coerceAtLeast(1),
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                bitmap
            }
            val out = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
            val bytes = out.toByteArray()
            if (bytes.size > 900_000) {
                TwentyOneDiag.log("P21-CFG", "fotka kontaktu je po zmenšení moc velká, vynechávám")
                null
            } else {
                bytes
            }
        } catch (e: Exception) {
            TwentyOneDiag.log("P21-CFG", "fotku kontaktu nejde přečíst: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun data(mime: String): ContentProviderOperation.Builder =
        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, mime)

    private fun contactIdForRawContact(context: Context, rawUri: Uri): String? {
        context.contentResolver.query(
            rawUri,
            arrayOf(ContactsContract.RawContacts.CONTACT_ID),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0).toString()
        }
        return null
    }
}
