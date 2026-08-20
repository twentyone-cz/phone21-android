/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.ui.main.contacts.viewmodel

import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.contacts.ContactLoader.Companion.LINPHONE_ADDRESS_BOOK_FRIEND_LIST
import org.linphone.core.Address
import org.linphone.core.Factory
import org.linphone.core.Friend
import org.linphone.core.FriendList
import org.linphone.core.FriendList.Status
import org.linphone.core.FriendPhoneNumber
import org.linphone.core.SubscribePolicy
import org.linphone.core.tools.Log
import org.linphone.ui.GenericViewModel
import org.linphone.ui.main.contacts.model.NewOrEditNumberOrAddressModel
import org.linphone.utils.Event
import org.linphone.utils.FileUtils
import androidx.core.net.toUri
import org.linphone.utils.LinphoneUtils

class ContactNewOrEditViewModel
    @UiThread
    constructor() : GenericViewModel() {
    companion object {
        private const val TAG = "[Contact New/Edit View Model]"

        const val TEMP_PICTURE_NAME = "new_contact_temp_picture.jpg"
    }

    private lateinit var friend: Friend

    val id = MutableLiveData<String>()

    val isEdit = MutableLiveData<Boolean>()

    val picturePath = MutableLiveData<String>()

    val firstName = MutableLiveData<String>()

    val lastName = MutableLiveData<String>()

    val sipAddresses = ArrayList<NewOrEditNumberOrAddressModel>()

    val hideSipAddresses = MutableLiveData<Boolean>()

    val phoneNumbers = ArrayList<NewOrEditNumberOrAddressModel>()

    val company = MutableLiveData<String>()

    val jobTitle = MutableLiveData<String>()

    val saveChangesEvent: MutableLiveData<Event<String>> by lazy {
        MutableLiveData()
    }

    val friendFoundEvent = MutableLiveData<Event<Boolean>>()

    val addNewNumberOrAddressFieldEvent = MutableLiveData<Event<NewOrEditNumberOrAddressModel>>()

    val removeNewNumberOrAddressFieldEvent = MutableLiveData<Event<NewOrEditNumberOrAddressModel>>()

    // fork: žádost o WRITE_CONTACTS těsně před uložením + potvrzení zápisu do systému
    val askWriteContactsPermissionEvent: MutableLiveData<Event<Boolean>> by lazy { MutableLiveData() }
    val savedToSystemEvent: MutableLiveData<Event<Boolean>> by lazy { MutableLiveData() }

    private val sipAddressesBeforeEdit = arrayListOf<String>()

    private val phoneNumbersBeforeEdit = arrayListOf<String>()

    private val sipAddressesLinkedToPresence = arrayListOf<String>()

    init {
        hideSipAddresses.postValue(corePreferences.hideSipAddresses)
    }

    @UiThread
    fun findFriendByRefKey(refKey: String?) {
        reset()

        coreContext.postOnCoreThread { core ->
            friend = if (refKey.isNullOrEmpty()) {
                core.createFriend()
            } else {
                coreContext.contactsManager.findContactById(refKey) ?: core.createFriend()
            }
            val exists = !friend.refKey.isNullOrEmpty()
            isEdit.postValue(exists)

            if (exists) {
                Log.i("$TAG Found friend [${friend.name}] using ref key [$refKey]")
                firstName.postValue(friend.firstName.orEmpty())
                lastName.postValue(friend.lastName.orEmpty())

                id.postValue(friend.refKey ?: friend.vcard?.uid)

                val photo = friend.photo.orEmpty()
                if (photo.isNotEmpty()) {
                    picturePath.postValue(photo)
                }

                for (address in friend.addresses) {
                    val sipAddress = address.asStringUriOnly()
                    // Prevents showing presence address as editable when in fact it's not
                    if (!LinphoneUtils.isSipAddressLinkedToPhoneNumberByPresence(friend, sipAddress)) {
                        addSipAddress(sipAddress)
                        sipAddressesBeforeEdit.add(sipAddress)
                    } else {
                        sipAddressesLinkedToPresence.add(sipAddress)
                    }
                }

                for (number in friend.phoneNumbersWithLabel) {
                    phoneNumbersBeforeEdit.add(number.phoneNumber)
                    addPhoneNumber(number.phoneNumber, number.label)
                }

                company.postValue(friend.organization)
                jobTitle.postValue(friend.jobTitle)
            } else if (refKey.orEmpty().isNotEmpty()) {
                Log.e("$TAG No friend found using ref key [$refKey]")
            }

            addSipAddress()
            addPhoneNumber()

            friendFoundEvent.postValue(Event(exists))
        }
    }

    @AnyThread
    fun getPictureFileName(): String {
        val name = id.value ?: "${firstName.value.orEmpty().trim()}_${lastName.value.orEmpty().trim()}"
        val flattenedName = name.replace(" ", "_").replace(":", "")
        return "$flattenedName.jpg"
    }

    @UiThread
    fun saveChanges() = saveChanges(forceLocal = false)

    @UiThread
    fun saveChanges(forceLocal: Boolean) {
        val fn = firstName.value.orEmpty().trim()
        val ln = lastName.value.orEmpty().trim()
        val organization = company.value.orEmpty().trim()
        if (fn.isEmpty() && ln.isEmpty() && organization.isEmpty()) {
            Log.e("$TAG At least a mandatory field wasn't filled, aborting save")
            showRedToast(R.string.contact_editor_mandatory_field_not_filled_toast, R.drawable.warning_circle)
            return
        }

        // fork: bez oprávnění nejdřív požádat; fragment po odpovědi zavolá
        // saveChanges(forceLocal = !granted) — zamítnutí = lokální uložení jako dřív
        if (!forceLocal &&
            !org.linphone.twentyone.contacts.TwentyOneContacts.hasWritePermission(coreContext.context)
        ) {
            askWriteContactsPermissionEvent.postValue(Event(true))
            return
        }

        coreContext.postOnCoreThread { core ->
            var status = Status.OK

            if (!::friend.isInitialized) {
                friend = core.createFriend()
            }
            val name = if (fn.isNotEmpty() && ln.isNotEmpty()) {
                "$fn $ln"
            } else {
                fn.ifEmpty {
                    ln.ifEmpty {
                        organization.ifEmpty {
                            "<Unknown>"
                        }
                    }
                }
            }

            // fork: primárně do systémového adresáře (auto/PBAP i Kontakty pak
            // vidí totéž). Výjimky nechávají upstream chování: nativní kontakt
            // (duplicita), kontakt z CardDAV listu (remove by smazal záznam
            // na serveru) a nové kontakty mířící na CardDAV dle nastavení.
            val editRefKey = friend.refKey
            val inCardDav = editRefKey != null && core.friendsLists.any {
                it.type == FriendList.Type.CardDAV && it.findFriendByRefKey(editRefKey) != null
            }
            val newGoesToCardDav = isEdit.value != true &&
                core.getFriendListByName(corePreferences.friendListInWhichStoreNewlyCreatedFriends)
                    ?.type == FriendList.Type.CardDAV
            if (!forceLocal && !inCardDav && !newGoesToCardDav && friend.nativeUri == null) {
                val phoneRows = phoneNumbers.mapNotNull {
                    val n = it.value.value.orEmpty().trim()
                    if (n.isEmpty()) {
                        null
                    } else {
                        org.linphone.twentyone.contacts.TwentyOneContacts.PhoneRow(n, it.label)
                    }
                }
                val sipRows = sipAddresses.mapNotNull {
                    it.value.value.orEmpty().trim().ifEmpty { null }
                }
                val photoBytes = org.linphone.twentyone.contacts.TwentyOneContacts.photoBytes(
                    coreContext.context, picturePath.value
                )
                val contactId = org.linphone.twentyone.contacts.TwentyOneContacts.insert(
                    coreContext.context, fn, ln, name, organization,
                    jobTitle.value.orEmpty().trim(), phoneRows, sipRows,
                    photo = photoBytes, starred = friend.starred
                )
                if (contactId != null) {
                    if (isEdit.value == true) {
                        // dřív aplikační kontakt — po přenosu do systému lokální
                        // smazat, jinak by ho seznam ukazoval dvakrát
                        coreContext.contactsManager.contactRemoved(friend)
                        friend.remove()
                    }
                    coreContext.contactsManager.notifyContactsListChanged()
                    savedToSystemEvent.postValue(Event(true))
                    return@postOnCoreThread
                }
                // selhání (GrapheneOS Contact Scopes apod.): kontakt NESMÍ
                // zmizet — pokračuje lokální větev, příčina je v deníku;
                // a migrace ho má příště zkusit přenést
                org.linphone.twentyone.contacts.TwentyOneContactsMigration.markPending(
                    coreContext.context
                )
            }

            friend.edit()
            friend.name = name
            friend.firstName = fn
            friend.lastName = ln

            val picture = picturePath.value.orEmpty()
            if (picture.isNotEmpty()) {
                if (picture.contains(TEMP_PICTURE_NAME)) {
                    val newFile = FileUtils.getFileStoragePath(
                        getPictureFileName(),
                        isImage = true,
                        overrideExisting = true
                    )
                    val oldFile = FileUtils.getProperFilePath(picture).toUri()
                    viewModelScope.launch {
                        FileUtils.copyFile(oldFile, newFile)
                    }
                    val newPicture = FileUtils.getProperFilePath(newFile.absolutePath)
                    Log.i("$TAG Temporary picture [$picture] copied to [$newPicture]")
                    friend.photo = newPicture
                } else {
                    friend.photo = FileUtils.getProperFilePath(picture)
                }
            } else {
                friend.photo = null
            }

            friend.organization = organization
            friend.jobTitle = jobTitle.value.orEmpty().trim()

            updateAddresses()
            updatePhoneNumbers()

            if (isEdit.value == false) {
                friend.vcard?.generateUniqueId()
                friend.refKey = friend.vcard?.uid
                Log.i(
                    "$TAG Newly created friend will have generated ref key [${friend.refKey}]"
                )

                friend.isSubscribesEnabled = false
                // Disable peer to peer short term presence
                friend.incSubscribePolicy = SubscribePolicy.SPDeny

                friend.done()

                val friendListNameToStoreFriends = corePreferences.friendListInWhichStoreNewlyCreatedFriends
                Log.i(
                    "$TAG Looking for friend list with name [$friendListNameToStoreFriends] to use to store newly created contact"
                )
                val friendList = core.getFriendListByName(friendListNameToStoreFriends) ?: core.getFriendListByName(
                    LINPHONE_ADDRESS_BOOK_FRIEND_LIST
                )
                val fl = friendList ?: core.createFriendList()
                if (friendList == null) {
                    if (friendListNameToStoreFriends != LINPHONE_ADDRESS_BOOK_FRIEND_LIST) {
                        Log.w(
                            "$TAG Locally saved friend list [$friendListNameToStoreFriends] didn't exist yet (nor [$LINPHONE_ADDRESS_BOOK_FRIEND_LIST]), let's create it"
                        )
                    } else {
                        Log.w(
                            "$TAG Locally saved friend list [$friendListNameToStoreFriends] didn't exist yet, let's create it"
                        )
                    }
                    fl.isDatabaseStorageEnabled = true // We do want to store friends created in app in DB
                    fl.displayName = LINPHONE_ADDRESS_BOOK_FRIEND_LIST
                    core.addFriendList(fl)
                }
                status = fl.addFriend(friend)
                if (status == Status.OK) {
                    Log.i("$TAG Contact successfully created, updating subscriptions")
                    fl.updateSubscriptions()

                    if (fl.type == FriendList.Type.CardDAV) {
                        Log.i(
                            "$TAG Contact successfully created into CardDAV friend list, synchronizing it"
                        )
                        fl.synchronizeFriendsFromServer()
                    }
                } else {
                    Log.e("$TAG Failed to add contact to friend list [${fl.displayName}]!")
                }
            } else {
                Log.i("$TAG Finished applying changes to existing friend")
                friend.done()
            }

            coreContext.contactsManager.newContactAdded(friend)
            coreContext.contactsManager.notifyContactsListChanged()

            saveChangesEvent.postValue(
                Event(if (status == Status.OK) friend.refKey.orEmpty() else "")
            )
        }
    }

    @WorkerThread
    fun addSipAddress(address: String = "", requestFieldToBeAddedInUi: Boolean = false) {
        val newModel = NewOrEditNumberOrAddressModel(address, true, "", {
            if (address.isEmpty()) {
                coreContext.postOnCoreThread {
                    addSipAddress(requestFieldToBeAddedInUi = true)
                }
            }
        }, { model ->
            coreContext.postOnCoreThread {
                removeModel(model)
            }
        })
        sipAddresses.add(newModel)

        if (requestFieldToBeAddedInUi) {
            addNewNumberOrAddressFieldEvent.postValue(Event(newModel))
        }
    }

    @WorkerThread
    private fun addPhoneNumber(
        number: String = "",
        label: String? = "",
        requestFieldToBeAddedInUi: Boolean = false
    ) {
        val newModel = NewOrEditNumberOrAddressModel(number, false, label, {
            if (number.isEmpty()) {
                coreContext.postOnCoreThread {
                    addPhoneNumber(requestFieldToBeAddedInUi = true)
                }
            }
        }, { model ->
            coreContext.postOnCoreThread {
                removeModel(model)
            }
        })
        phoneNumbers.add(newModel)

        if (requestFieldToBeAddedInUi) {
            addNewNumberOrAddressFieldEvent.postValue(Event(newModel))
        }
    }

    @WorkerThread
    private fun removeModel(model: NewOrEditNumberOrAddressModel) {
        if (model.isSip) {
            sipAddresses.remove(model)
        } else {
            phoneNumbers.remove(model)
        }
        removeNewNumberOrAddressFieldEvent.postValue(Event(model))
    }

    @UiThread
    private fun reset() {
        isEdit.value = false
        picturePath.value = ""
        firstName.value = ""
        lastName.value = ""
        sipAddresses.clear()
        phoneNumbers.clear()
        company.value = ""
        jobTitle.value = ""
        sipAddressesBeforeEdit.clear()
        phoneNumbersBeforeEdit.clear()
        sipAddressesLinkedToPresence.clear()
    }

    @UiThread
    fun isPendingChanges(): Boolean {
        if (isEdit.value == true) {
            Log.i("$TAG Contact is being edited, checking if at least one field has changed")
            if (firstName.value.orEmpty() != friend.firstName.orEmpty()) return true
            if (lastName.value.orEmpty() != friend.lastName.orEmpty()) return true
            if (picturePath.value.orEmpty() != friend.photo.orEmpty()) return true
            if (company.value.orEmpty() != friend.organization.orEmpty()) return true
            if (jobTitle.value.orEmpty() != friend.jobTitle.orEmpty()) return true

            for (sipAddress in sipAddressesBeforeEdit) {
                if (sipAddressesLinkedToPresence.contains(sipAddress)) continue

                val found = sipAddresses.find {
                    it.isSip && it.value.value.orEmpty() == sipAddress
                }
                if (found == null) return true
            }
            for (address in sipAddresses) {
                val sipAddress = address.value.value.orEmpty()
                if (sipAddress.isEmpty()) continue

                val found = sipAddressesBeforeEdit.find {
                    it == sipAddress
                }
                if (found == null) return true
            }
            for (number in phoneNumbersBeforeEdit) {
                val found = phoneNumbers.find {
                    !it.isSip && it.value.value.orEmpty() == number
                }
                if (found == null) return true
            }
            for (number in phoneNumbers) {
                if (number.value.value.orEmpty().isEmpty()) continue

                val found = phoneNumbersBeforeEdit.find {
                    it == number.value.value.orEmpty()
                }
                if (found == null) return true
            }

            return false
        }

        Log.i("$TAG Checking if at least a field of the new contact form has been filled")
        return !picturePath.value.isNullOrEmpty() ||
            !firstName.value.isNullOrEmpty() ||
            !lastName.value.isNullOrEmpty() ||
            !sipAddresses.firstOrNull()?.value?.value.isNullOrEmpty() ||
            !phoneNumbers.firstOrNull()?.value?.value.isNullOrEmpty() ||
            !company.value.isNullOrEmpty() ||
            !jobTitle.value.isNullOrEmpty()
    }

    @WorkerThread
    private fun updateAddresses() {
        val core = coreContext.core

        val toKeep = arrayListOf<Address>()
        for (address in sipAddresses) {
            val data = address.value.value.orEmpty().trim()
            if (data.isNotEmpty()) {
                val parsedAddress = core.interpretUrl(data, false)
                if (parsedAddress != null) {
                    toKeep.add(parsedAddress)
                }
            }
        }
        val toRemove = arrayListOf<Address>()
        val toAdd = arrayListOf<Address>()

        for (newAddress in toKeep) {
            var found = false
            for (oldAddress in friend.addresses) {
                if (oldAddress.weakEqual(newAddress)) {
                    found = true
                    break
                }
            }
            if (!found) {
                Log.i(
                    "$TAG Address [${newAddress.asStringUriOnly()}] doesn't exist yet in friend, adding it"
                )
                toAdd.add(newAddress)
            }
        }
        for (oldAddress in friend.addresses) {
            var found = false
            for (newAddress in toKeep) {
                if (oldAddress.weakEqual(newAddress)) {
                    found = true
                    break
                }
            }
            if (!found) {
                Log.i(
                    "$TAG Address [${oldAddress.asStringUriOnly()}] no longer exists, removing it"
                )
                toRemove.add(oldAddress)
            }
        }
        for (address in toRemove) {
            friend.removeAddress(address)
        }
        for (address in toAdd) {
            friend.addAddress(address)
        }
    }

    @WorkerThread
    private fun updatePhoneNumbers() {
        val toKeep = arrayListOf<FriendPhoneNumber>()
        for (number in phoneNumbers) {
            val data = number.value.value.orEmpty().trim()
            val label = number.label.orEmpty()
            if (data.isNotEmpty()) {
                val phoneNumber = Factory.instance().createFriendPhoneNumber(data, label)
                toKeep.add(phoneNumber)
            }
        }
        val toRemove = arrayListOf<FriendPhoneNumber>()
        val toAdd = arrayListOf<FriendPhoneNumber>()

        for (newNumber in toKeep) {
            var found = false
            for (oldNumber in friend.phoneNumbersWithLabel) {
                if (oldNumber.phoneNumber == newNumber.phoneNumber) {
                    found = true
                    break
                }
            }
            if (!found) {
                Log.i(
                    "$TAG Phone number [${newNumber.phoneNumber}] doesn't exist yet in friend, adding it"
                )
                toAdd.add(newNumber)
            }
        }
        for (oldNumber in friend.phoneNumbersWithLabel) {
            var found = false
            for (newNumber in toKeep) {
                if (oldNumber.phoneNumber == newNumber.phoneNumber && (newNumber.label.isNullOrEmpty() || newNumber.label == oldNumber.label)) {
                    found = true
                    break
                }
            }
            if (!found) {
                Log.i(
                    "$TAG Phone number [${oldNumber.phoneNumber}](${oldNumber.label}) no longer exists, removing it"
                )
                toRemove.add(oldNumber)
            }
        }
        for (address in toRemove) {
            friend.removePhoneNumberWithLabel(address)
        }
        for (address in toAdd) {
            friend.addPhoneNumberWithLabel(address)
        }
    }
}
