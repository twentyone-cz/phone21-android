/*
 * fork: jednorázová migrace aplikačních kontaktů do systémového adresáře.
 * Prochází friend listy (mimo nativní zrcadlo, cache a CardDAV), jméno,
 * čísla a SIP adresy přenese do systému a lokální friend smaže. Pojistka
 * proti opakování: SharedPreferences flag; nastaví se jen po běhu bez chyb.
 */
package org.linphone.twentyone.contacts

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.contacts.ContactLoader.Companion.NATIVE_ADDRESS_BOOK_FRIEND_LIST
import org.linphone.core.FriendList
import org.linphone.twentyone.TwentyOneDiag

object TwentyOneContactsMigration {
    private const val PREFS_FILE = "twentyone_contacts"
    private const val KEY_DONE = "migration_done"

    private val running = AtomicBoolean(false)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** Lokálně uložený kontakt čeká na přenos — příští běh migrace ho vezme. */
    fun markPending(context: Context) {
        prefs(context).edit().putBoolean(KEY_DONE, false).apply()
    }

    /** Bezpečné volat opakovaně; skutečný běh jen jednou a jen s oprávněním. */
    fun runIfNeeded(context: Context) {
        if (prefs(context).getBoolean(KEY_DONE, false)) return
        if (!TwentyOneContacts.hasWritePermission(context)) return
        if (!running.compareAndSet(false, true)) return

        coreContext.postOnCoreThread { core ->
            var moved = 0
            var skippedDuplicates = 0
            var failed = 0
            try {
                for (list in core.friendsLists) {
                    if (list.displayName == NATIVE_ADDRESS_BOOK_FRIEND_LIST) continue
                    if (list.type == FriendList.Type.ApplicationCache) continue
                    if (list.type == FriendList.Type.CardDAV) continue

                    for (friend in list.friends) {
                        if (friend.nativeUri != null) continue // už je nativní

                        val displayName = friend.name.orEmpty().ifEmpty {
                            "${friend.firstName.orEmpty()} ${friend.lastName.orEmpty()}".trim()
                        }
                        val phones = friend.phoneNumbersWithLabel.map {
                            TwentyOneContacts.PhoneRow(it.phoneNumber, it.label)
                        }
                        val photo = TwentyOneContacts.photoBytes(context, friend.photo)

                        // duplicita jen když systém prokazatelně nese VŠECHNA
                        // čísla pod stejným jménem a lokál nemá nic navíc
                        // (fotku, SIP adresy) — jinak radši vložit než zahodit
                        val duplicate = phones.isNotEmpty() && photo == null &&
                            friend.addresses.isEmpty() &&
                            phones.all {
                                TwentyOneContacts.existsByNumberAndName(
                                    context, it.number, displayName
                                )
                            }
                        if (duplicate) {
                            skippedDuplicates++
                            friend.remove()
                            continue
                        }

                        val contactId = TwentyOneContacts.insert(
                            context,
                            friend.firstName.orEmpty(),
                            friend.lastName.orEmpty(),
                            displayName,
                            friend.organization.orEmpty(),
                            friend.jobTitle.orEmpty(),
                            phones,
                            friend.addresses.map { it.asStringUriOnly() },
                            photo = photo,
                            starred = friend.starred
                        )
                        if (contactId != null) {
                            friend.remove()
                            moved++
                        } else {
                            failed++
                            // první selhání = zápisy nejspíš blokované (Contact
                            // Scopes), nemá smysl mlátit provider dalšími pokusy
                            break
                        }
                    }
                    if (failed > 0) break
                }

                if (failed == 0) {
                    prefs(context).edit().putBoolean(KEY_DONE, true).apply()
                }
                if (moved > 0 || skippedDuplicates > 0 || failed > 0) {
                    TwentyOneDiag.log(
                        "P21-CFG",
                        "migrace kontaktů do systému: převedeno $moved, " +
                            "duplicit $skippedDuplicates, selhalo $failed"
                    )
                    coreContext.contactsManager.notifyContactsListChanged()
                }
            } finally {
                running.set(false)
            }
        }
    }
}
