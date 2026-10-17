package com.nuvio.app.core.debug

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.CoreFoundation.*
import platform.Security.*

/**
 * The Apple Account Nuvio Z renews itself with, in this iPhone's Keychain: this device only (never in a backup or
 * iCloud Keychain), readable after the first unlock so a Shortcuts automation or a background run can use it while
 * the phone is locked. Saved only when the person ticks "Remember" in the renew dialog; nothing else stores it.
 */
@OptIn(ExperimentalForeignApi::class)
internal object SelfSigningKeychain {
    @Serializable
    data class Account(val email: String, val password: String)

    private const val SERVICE = "com.nuvio.app.selfsigning"
    private const val ACCOUNT = "apple-account"

    fun read(): Account? = runCatching {
        query { query ->
            CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
            CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
            memScoped {
                val result = alloc<CFTypeRefVar>()
                if (SecItemCopyMatching(query, result.ptr) != errSecSuccess) return@memScoped null
                val data: CFDataRef = result.value?.reinterpret() ?: return@memScoped null
                try {
                    val bytes = CFDataGetBytePtr(data) ?: return@memScoped null
                    ByteArray(CFDataGetLength(data).toInt()) { bytes[it].toByte() }.decodeToString()
                } finally {
                    CFRelease(data)
                }
            }
        }?.let { Json.decodeFromString(Account.serializer(), it) }
    }.getOrNull()

    fun save(account: Account) {
        val bytes = Json.encodeToString(Account.serializer(), account).encodeToByteArray().toUByteArray()
        val data = CFDataCreate(null, bytes.refTo(0), bytes.size.toLong()) ?: error("Unable to encode the Apple Account")
        try {
            query { query ->
                val attributes = dictionary()
                try {
                    CFDictionarySetValue(attributes, kSecValueData, data)
                    val update = SecItemUpdate(query, attributes)
                    if (update == errSecItemNotFound) {
                        CFDictionarySetValue(query, kSecValueData, data)
                        CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                        val added = SecItemAdd(query, null)
                        check(added == errSecSuccess) { "Unable to save the Apple Account (OSStatus $added)" }
                    } else {
                        check(update == errSecSuccess) { "Unable to update the Apple Account (OSStatus $update)" }
                    }
                } finally {
                    CFRelease(attributes)
                }
            }
        } finally {
            CFRelease(data)
        }
    }

    fun clear() {
        query { SecItemDelete(it) }
    }

    private fun dictionary(): CFMutableDictionaryRef =
        CFDictionaryCreateMutable(null, 0L, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
            ?: error("Unable to create a Keychain query")

    private inline fun <T> query(block: (CFMutableDictionaryRef) -> T): T {
        val service = CFStringCreateWithCString(null, SERVICE, kCFStringEncodingUTF8) ?: error("Unable to encode the service")
        val account = CFStringCreateWithCString(null, ACCOUNT, kCFStringEncodingUTF8) ?: error("Unable to encode the account")
        val query = dictionary()
        try {
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrService, service)
            CFDictionarySetValue(query, kSecAttrAccount, account)
            return block(query)
        } finally {
            CFRelease(query)
            CFRelease(account)
            CFRelease(service)
        }
    }
}
