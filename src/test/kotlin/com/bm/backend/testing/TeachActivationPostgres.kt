package com.bm.backend.testing

import com.bm.backend.models.TeachActiveSkill
import com.bm.backend.services.GESTERNOVA_SUPPLIER
import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.PortBinding
import com.github.dockerjava.api.model.Ports
import com.github.dockerjava.api.exception.NotFoundException
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** This fixture owns both DBs and their teardown. Never reads service configuration. */
internal class TeachActivationPostgres(private val manifest: String, withProposals: Boolean = false,
    promotionProviderId: UUID? = null) : AutoCloseable {
    val storeId = UUID.randomUUID().toString().replace("-", "")
    private val id = storeId.take(12)
    val qaDb = "qa_pilot_$id"
    val sentinelDb = "prod_sentinel_$id"
    val qaRole = "qa_writer_$id"
    val readerRole = "qa_reader_$id"
    val sentinelRole = "sentinel_owner_$id"
    private val qaPassword = UUID.randomUUID().toString()
    private val readerPassword = UUID.randomUUID().toString()
    private val sentinelPassword = UUID.randomUUID().toString()
    private var startedContainer: String? = null
    private var ownedVolumes = emptyList<String>()
    private val clockedSockets = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    private val container = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("fixture_admin")
        .withUsername("fixture_owner")
        .withPassword(UUID.randomUUID().toString())
        .withReuse(false)
        .withCreateContainerCmdModifier { command ->
            checkNotNull(command.hostConfig).withPortBindings(
                PortBinding(Ports.Binding.bindIp("127.0.0.1"), ExposedPort.tcp(5432)),
            )
        }
    val proposals = if (withProposals) TeachProposalPostgres(this, manifest, ::owner, promotionProviderId) { db, role, password, remaining, checkpoint ->
        TeachActivationDeadlineSocketFactory.connect(clockedSockets, remaining, checkpoint) {
            source(db, role, password, 0).apply { socketFactory = TeachActivationDeadlineSocketFactory::class.java.name }.connection
        }
    } else null

    init {
        try {
            container.start() // Mandatory: never skip if Docker is unavailable.
            startedContainer = container.containerId
            val info = container.containerInfo
            ownedVolumes = info.mounts.orEmpty().mapNotNull { it.name?.takeIf(String::isNotBlank) }
            check(container.host in setOf("localhost", "127.0.0.1"))
            check(info.networkSettings.ports.bindings.getValue(ExposedPort.tcp(5432)).single().hostIp == "127.0.0.1")
            owner(container.databaseName) { sql ->
                sql.exec("CREATE ROLE $qaRole LOGIN PASSWORD '$qaPassword'")
                sql.exec("CREATE ROLE $readerRole LOGIN PASSWORD '$readerPassword'")
                sql.exec("CREATE ROLE $sentinelRole LOGIN PASSWORD '$sentinelPassword'")
                sql.exec("CREATE DATABASE $qaDb")
                sql.exec("CREATE DATABASE $sentinelDb OWNER $sentinelRole")
                for (db in listOf(qaDb, sentinelDb, "postgres", "template1", container.databaseName)) {
                    sql.exec("REVOKE ALL ON DATABASE $db FROM PUBLIC")
                }
                sql.exec("GRANT CONNECT ON DATABASE $qaDb TO $qaRole")
                sql.exec("GRANT CONNECT ON DATABASE $qaDb TO $readerRole")
            }
            owner(qaDb) { sql ->
                sql.exec("CREATE SCHEMA pilot")
                sql.exec("CREATE TABLE pilot.identity (id text PRIMARY KEY, manifest text NOT NULL)")
                sql.prepareStatement("INSERT INTO pilot.identity VALUES (?, ?)").use {
                    it.setString(1, storeId); it.setString(2, manifest); it.executeUpdate()
                }
                createCatalog(sql, "qa")
                sql.exec("GRANT USAGE ON SCHEMA pilot TO $qaRole")
                sql.exec("GRANT SELECT ON pilot.identity, pilot.active, pilot.audit TO $qaRole")
                sql.exec("GRANT INSERT, UPDATE ON pilot.active TO $qaRole")
                sql.exec("GRANT INSERT ON pilot.audit TO $qaRole")
                sql.exec("GRANT USAGE ON SCHEMA pilot TO $readerRole")
                sql.exec("GRANT SELECT ON pilot.identity, pilot.active TO $readerRole")
            }
            sentinel { sql -> sql.exec("CREATE SCHEMA pilot"); createCatalog(sql, "prod") }
            proposals?.provision(container.databaseName)
        } catch (failure: Throwable) {
            runCatching { close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun createCatalog(sql: Connection, environment: String) {
        sql.exec("""CREATE TABLE pilot.active (
            supplier text PRIMARY KEY, revision text NOT NULL CHECK (revision ~ '^[0-9a-f]{64}$'),
            generation bigint NOT NULL CHECK (generation > 0))""")
        sql.exec("""CREATE TABLE pilot.audit (
            nonce text PRIMARY KEY, supplier text NOT NULL, revision text NOT NULL, previous text,
            generation bigint NOT NULL, actor text NOT NULL,
            environment text NOT NULL CHECK (environment = '$environment'), occurred_at timestamptz NOT NULL,
            outcome text NOT NULL CHECK (outcome = 'activated'))""")
    }

    private fun source(database: String, role: String, password: String, seconds: Int) = PGSimpleDataSource().apply {
        setServerNames(arrayOf("127.0.0.1"))
        setPortNumbers(intArrayOf(container.getMappedPort(5432)))
        databaseName = database
        user = role
        this.password = password
        sslMode = "disable"
        connectTimeout = seconds
        loginTimeout = seconds
        socketTimeout = seconds
    }

    private fun <T> owner(db: String, run: (Connection) -> T): T =
        source(db, container.username, container.password, 5).connection.use(run)

    fun <T> qaOwner(run: (Connection) -> T): T = owner(qaDb, run)
    fun <T> sentinel(run: (Connection) -> T): T = source(sentinelDb, sentinelRole, sentinelPassword, 5).connection.use(run)
    fun qaConnection(db: String = qaDb, seconds: Int = 5): Connection = source(db, qaRole, qaPassword, seconds).connection
    fun readerConnection(db: String = qaDb): Connection = source(db, readerRole, readerPassword, 5).connection

    fun reader(database: String = qaDb, role: String = readerRole, id: String = storeId) =
        TeachActiveSkillJdbcReader(id, database, role) { remainingMillis, checkpoint ->
            TeachActivationDeadlineSocketFactory.connect(clockedSockets, remainingMillis, checkpoint) {
                source(qaDb, readerRole, readerPassword, 0).apply {
                    socketFactory = TeachActivationDeadlineSocketFactory::class.java.name
                }.connection
            }
        }

    fun store(database: String = qaDb, role: String = qaRole, id: String = storeId,
              beforeLock: (String) -> Unit = {}, afterRead: (TeachActiveSkill) -> Unit = {},
              beforeCommit: (TeachActiveSkill) -> Unit = {}) =
        TeachActivationJdbcStore(id, database, role, { remainingMillis ->
            TeachActivationDeadlineSocketFactory.connect(clockedSockets, remainingMillis) {
                // Nonzero loginTimeout would let PgJDBC abandon a still-running connector thread.
                source(qaDb, qaRole, qaPassword, 0).apply {
                    socketFactory = TeachActivationDeadlineSocketFactory::class.java.name
                }.connection
            }
        }, beforeLock, afterRead, beforeCommit)

    fun seed(sql: Connection, revision: String, generation: Long = 1) {
        sql.prepareStatement("INSERT INTO pilot.active VALUES (?, ?, ?)").use {
            it.setString(1, GESTERNOVA_SUPPLIER); it.setString(2, revision); it.setLong(3, generation); it.executeUpdate()
        }
    }

    fun snapshot(sql: Connection): List<List<List<String?>>> {
        sql.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
        sql.autoCommit = false
        return try {
            listOf(
                "SELECT supplier, revision, generation FROM pilot.active ORDER BY supplier",
                "SELECT nonce, supplier, revision, previous, generation, actor, environment, occurred_at::text, outcome FROM pilot.audit ORDER BY generation",
            ).map { query -> sql.createStatement().use { statement -> statement.executeQuery(query).use { rows ->
                buildList { while (rows.next()) add((1..rows.metaData.columnCount).map(rows::getString)) }
            } } }
        } finally { sql.rollback() }
    }

    override fun close() {
        try { container.stop() } finally {
            clockedSockets.shutdownNow()
            check(clockedSockets.awaitTermination(3, TimeUnit.SECONDS)) { "Pilot socket deadline executor survived teardown" }
        }
        val id = startedContainer ?: return
        val docker = DockerClientFactory.instance().client()
        fun gone(inspect: () -> Unit): Boolean = try { inspect(); false } catch (_: NotFoundException) { true }
        check(gone { docker.inspectContainerCmd(id).exec() }) { "Pilot PostgreSQL container survived teardown" }
        check(ownedVolumes.all { volume -> gone { docker.inspectVolumeCmd(volume).exec() } }) { "Pilot PostgreSQL volume survived teardown" }
    }
}

internal fun Connection.exec(sql: String) { createStatement().use { it.execute(sql) } }
