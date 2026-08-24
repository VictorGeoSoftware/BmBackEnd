package com.bm.backend.services

import com.bm.backend.testing.DirectTransactionRunner
import com.bm.backend.testing.InMemoryGrantedUsersRepository
import com.bm.backend.testing.InMemoryUserActivityRepository
import com.bm.backend.testing.InMemoryUserConsumptionRepository
import com.bm.backend.testing.InMemoryUserDataRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GrantedUsersServiceTest {

    private class RecordingUserAccountRevoker : UserAccountRevoker {
        val revokedUids = mutableListOf<String>()
        override suspend fun revokeRefreshTokens(uid: String) {
            revokedUids.add(uid)
        }
    }

    private class RecordingForceLogoutNotifier : ForceLogoutNotifier {
        val notifiedEmails = mutableListOf<String>()
        override suspend fun notifyForceLogout(email: String) {
            notifiedEmails.add(email)
        }
    }

    private lateinit var grantedUsersRepository: InMemoryGrantedUsersRepository
    private lateinit var userDataRepository: InMemoryUserDataRepository
    private lateinit var userActivityRepository: InMemoryUserActivityRepository
    private lateinit var userConsumptionRepository: InMemoryUserConsumptionRepository
    private lateinit var userAccountRevoker: RecordingUserAccountRevoker
    private lateinit var forceLogoutNotifier: RecordingForceLogoutNotifier
    private lateinit var transactionRunner: DirectTransactionRunner
    private lateinit var service: GrantedUsersService

    @BeforeEach
    fun setUp() {
        grantedUsersRepository = InMemoryGrantedUsersRepository()
        userDataRepository = InMemoryUserDataRepository()
        userActivityRepository = InMemoryUserActivityRepository()
        userConsumptionRepository = InMemoryUserConsumptionRepository()
        userAccountRevoker = RecordingUserAccountRevoker()
        forceLogoutNotifier = RecordingForceLogoutNotifier()
        transactionRunner = DirectTransactionRunner()
        service = GrantedUsersService(
            grantedUsersRepository = grantedUsersRepository,
            userDataRepository = userDataRepository,
            userActivityRepository = userActivityRepository,
            userConsumptionRepository = userConsumptionRepository,
            userAccountRevoker = userAccountRevoker,
            forceLogoutNotifier = forceLogoutNotifier,
            transactionRunner = transactionRunner
        )
    }

    @Test
    fun `addGrant normalizes email and inserts`() {
        val result = service.addGrant("  Tester@Example.com ")
        assertIs<GrantedUsersService.AddGrantResult.Added>(result)
        assertEquals("tester@example.com", result.email)
        assertTrue(grantedUsersRepository.existsByEmail("tester@example.com"))
    }

    @Test
    fun `addGrant rejects invalid email`() {
        assertIs<GrantedUsersService.AddGrantResult.InvalidEmail>(service.addGrant(null))
        assertIs<GrantedUsersService.AddGrantResult.InvalidEmail>(service.addGrant(""))
        assertIs<GrantedUsersService.AddGrantResult.InvalidEmail>(service.addGrant("not-an-email"))
    }

    @Test
    fun `addGrant reports duplicates case-insensitively`() {
        service.addGrant("tester@example.com")
        val result = service.addGrant("TESTER@example.COM")
        assertIs<GrantedUsersService.AddGrantResult.AlreadyExists>(result)
        assertEquals("tester@example.com", result.email)
    }

    @Test
    fun `listGrants joins activity data when the user has logged in`() {
        service.addGrant("tester@example.com")
        userActivityRepository.setOnline(name = "Tester", email = "tester@example.com")

        val users = service.listGrants()
        assertEquals(1, users.size)
        val user = users.single()
        assertEquals("tester@example.com", user.email)
        assertEquals("Tester", user.name)
        assertEquals(true, user.isOnline)
        assertEquals(0, user.monthlyUsageCount)
    }

    @Test
    fun `listGrants returns null activity fields for never-logged-in grants`() {
        service.addGrant("newbie@example.com")

        val user = service.listGrants().single()
        assertEquals("newbie@example.com", user.email)
        assertNull(user.name)
        assertNull(user.isOnline)
        assertNull(user.monthlyUsageCount)
        assertNull(user.usageStartedAt)
    }

    @Test
    fun `deleteGrant wipes all user data, revokes tokens and notifies`() = runBlocking {
        service.addGrant("tester@example.com")
        userDataRepository.upsertUserData(
            uid = "uid-123",
            email = "tester@example.com",
            displayName = "Tester",
            photoURL = null,
            providerIds = listOf("google.com"),
            tokenIssuedAt = Instant.now(),
            tokenExpiresAt = Instant.now(),
            phoneUuid = "phone-1"
        )
        userActivityRepository.setOnline(name = "Tester", email = "tester@example.com")

        val result = service.deleteGrant("TESTER@example.com")

        assertIs<GrantedUsersService.DeleteGrantResult.Deleted>(result)
        assertEquals("tester@example.com", result.email)

        // Grant, user_data and user_activity rows are gone
        assertTrue(grantedUsersRepository.findAll().isEmpty())
        assertNull(userDataRepository.findUidByEmail("tester@example.com"))
        assertTrue(userActivityRepository.getUsersActivity().isEmpty())

        // Consumption wipe requested for the resolved uid
        assertEquals(listOf("uid-123"), userConsumptionRepository.deletedUids)

        // Firebase sessions revoked and force-logout broadcast sent
        assertEquals(listOf("uid-123"), userAccountRevoker.revokedUids)
        assertEquals(listOf("tester@example.com"), forceLogoutNotifier.notifiedEmails)

        // The four-table wipe must happen as ONE unit of work: a partial delete
        // would leave an account able to authenticate with its data half gone.
        assertEquals(1, transactionRunner.executions)
    }

    @Test
    fun `deleteGrant skips token revocation when user never synced`() = runBlocking {
        service.addGrant("newbie@example.com")

        val result = service.deleteGrant("newbie@example.com")

        assertIs<GrantedUsersService.DeleteGrantResult.Deleted>(result)
        assertTrue(grantedUsersRepository.findAll().isEmpty())
        assertTrue(userAccountRevoker.revokedUids.isEmpty())
        assertTrue(userConsumptionRepository.deletedUids.isEmpty())
        assertEquals(listOf("newbie@example.com"), forceLogoutNotifier.notifiedEmails)
    }

    @Test
    fun `deleteGrant reports not found and touches nothing`() = runBlocking {
        val result = service.deleteGrant("ghost@example.com")

        assertIs<GrantedUsersService.DeleteGrantResult.NotFound>(result)
        assertTrue(userAccountRevoker.revokedUids.isEmpty())
        assertTrue(userConsumptionRepository.deletedUids.isEmpty())
        assertTrue(forceLogoutNotifier.notifiedEmails.isEmpty())
    }

    @Test
    fun `deleteGrant rejects invalid email`() = runBlocking {
        assertIs<GrantedUsersService.DeleteGrantResult.InvalidEmail>(service.deleteGrant(null))
        assertIs<GrantedUsersService.DeleteGrantResult.InvalidEmail>(service.deleteGrant("nope"))
    }
}
