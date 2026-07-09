package com.bm.backend.services

import com.bm.backend.testing.InMemoryUserDataRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import kotlin.test.assertEquals

/**
 * Service-level unit tests for the one-phone-per-account rule and the
 * administrative device-binding reset, exercised through an in-memory fake of
 * [com.bm.backend.repositories.ports.UserDataRepositoryPort] (no database).
 */
class UserDataServiceTest {

    private fun sync(
        service: UserDataService,
        uid: String,
        email: String? = "user@example.com",
        phoneUuid: String?
    ) {
        service.upsertUserData(
            uid = uid,
            email = email,
            displayName = null,
            photoURL = null,
            providerIds = listOf("google.com"),
            tokenIssuedAt = Instant.ofEpochSecond(1L),
            tokenExpiresAt = Instant.ofEpochSecond(2L),
            phoneUuid = phoneUuid
        )
    }

    @Test
    fun `binds the first device and accepts subsequent logins from the same device`() {
        val service = UserDataService(InMemoryUserDataRepository())

        sync(service, uid = "uid-1", phoneUuid = "device-a")
        // Same device logs in again -> allowed, no exception.
        sync(service, uid = "uid-1", phoneUuid = "device-a")
    }

    @Test
    fun `rejects a login from a different device with DeviceMismatchException`() {
        val service = UserDataService(InMemoryUserDataRepository())

        sync(service, uid = "uid-1", phoneUuid = "device-a")

        assertThrows<DeviceMismatchException> {
            sync(service, uid = "uid-1", phoneUuid = "device-b")
        }
    }

    @Test
    fun `treats a blank or missing incoming device as no assertion (backward compatible)`() {
        val fake = InMemoryUserDataRepository()
        val service = UserDataService(fake)

        sync(service, uid = "uid-1", phoneUuid = "device-a")
        // Older client without a device id -> allowed, binding is retained.
        sync(service, uid = "uid-1", phoneUuid = null)
        sync(service, uid = "uid-1", phoneUuid = "   ")

        assertEquals("device-a", fake.findPhoneUuid("uid-1"))
    }

    @Test
    fun `allows a replacement device to bind after an admin reset`() {
        val service = UserDataService(InMemoryUserDataRepository())

        sync(service, uid = "uid-1", email = "user@example.com", phoneUuid = "device-a")

        val reset = service.resetDeviceBinding("USER@example.com ")
        assertEquals(1, reset)

        // New device now binds successfully instead of being rejected.
        sync(service, uid = "uid-1", email = "user@example.com", phoneUuid = "device-b")
    }

    @Test
    fun `resetDeviceBinding returns zero when no account matches`() {
        val service = UserDataService(InMemoryUserDataRepository())
        sync(service, uid = "uid-1", email = "user@example.com", phoneUuid = "device-a")

        assertEquals(0, service.resetDeviceBinding("nobody@example.com"))
    }

    @Test
    fun `resetDeviceBinding rejects a blank email`() {
        val service = UserDataService(InMemoryUserDataRepository())
        assertThrows<IllegalArgumentException> {
            service.resetDeviceBinding("   ")
        }
    }
}
