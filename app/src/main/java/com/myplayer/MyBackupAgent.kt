package com.myplayer

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.ParcelFileDescriptor

/** Full-backup agent that makes Android Auto Backup opt-in at runtime: the manifest enables backup,
 *  but the actual upload only happens when the user turned it on ([Settings.isBackupEnabled]).
 *  With it off, nothing is uploaded, so uninstalling wipes everything. Which files are included
 *  (app.db, not cache.db) is still governed by `res/xml/data_extraction_rules.xml`. */
class MyBackupAgent : BackupAgent() {

    override fun onFullBackup(data: FullBackupDataOutput) {
        // Reads the flag straight from the DB (this agent process has a cold Settings cache).
        if (Settings.isBackupEnabled(this)) super.onFullBackup(data)
    }

    // Key/value backup is unused (the app uses full backup only); required no-op implementations.
    override fun onBackup(
        oldState: ParcelFileDescriptor?,
        data: BackupDataOutput?,
        newState: ParcelFileDescriptor?
    ) {
    }

    override fun onRestore(
        data: BackupDataInput?,
        appVersionCode: Int,
        newState: ParcelFileDescriptor?
    ) {
    }
}
