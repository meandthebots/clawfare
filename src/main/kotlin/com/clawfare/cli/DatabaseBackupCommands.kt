package com.clawfare.cli

import com.clawfare.db.ClawfareDatabase
import com.clawfare.db.EncryptedSqliteBackups
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.nio.file.Path
import java.util.concurrent.Callable

/**
 * Offline recovery commands never initialize/migrate a database implicitly.
 * In particular, restore requires a new path and cannot replace the live DB.
 */
@Command(
    name = "db",
    description = ["Create encrypted SQLite backups or restore into a new offline database"],
    mixinStandardHelpOptions = true,
    subcommands = [DbBackupCommand::class, DbRestoreCommand::class],
)
class DbCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: ClawfareCommand

    override fun call(): Int {
        CommandLine(this).usage(System.out)
        return 0
    }
}

@Command(name = "backup", description = ["Create a WAL-consistent encrypted snapshot without live-provider calls"])
class DbBackupCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: DbCommand

    @Option(names = ["--out"], required = true, description = ["New encrypted archive path (must not exist)"])
    lateinit var out: String

    @Option(names = ["--passphrase-file"], required = true, description = ["Owner-only local UTF-8 passphrase file"])
    lateinit var passphraseFile: String

    override fun call(): Int =
        try {
            val source = parent.parent.dbPath.ifBlank { ClawfareDatabase.defaultPath }
            withBackupPassphrase(passphraseFile) { password ->
                EncryptedSqliteBackups.backup(Path.of(source), Path.of(out), password)
            }
            println("Encrypted SQLite backup created: $out")
            0
        } catch (failure: Exception) {
            System.err.println("Encrypted database backup failed: ${failure.message}")
            2
        }
}

@Command(name = "restore", description = ["Decrypt and verify an archive into a new offline path only"])
class DbRestoreCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: DbCommand

    @Option(names = ["--from"], required = true, description = ["Encrypted archive to decrypt"])
    lateinit var from: String

    @Option(names = ["--into"], required = true, description = ["New SQLite file path (must not exist)"])
    lateinit var into: String

    @Option(names = ["--passphrase-file"], required = true, description = ["Owner-only local UTF-8 passphrase file"])
    lateinit var passphraseFile: String

    override fun call(): Int =
        try {
            withBackupPassphrase(passphraseFile) { password ->
                EncryptedSqliteBackups.restore(Path.of(from), Path.of(into), password)
            }
            println("Offline SQLite recovery file created: $into")
            println("The recovered database was not activated or migrated. Verify it independently before any manual replacement.")
            0
        } catch (failure: Exception) {
            System.err.println("Encrypted database restore failed: ${failure.message}")
            2
        }
}

private inline fun withBackupPassphrase(
    filename: String,
    operation: (CharArray) -> Unit,
) {
    val password = EncryptedSqliteBackups.passphraseFromFile(Path.of(filename))
    try {
        operation(password)
    } finally {
        password.fill('\u0000')
    }
}
