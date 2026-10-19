package dev.anydm.engine.store

import dev.anydm.engine.core.ProfileId
import dev.anydm.engine.files.NewProfile
import dev.anydm.engine.files.Profile
import dev.anydm.engine.files.ProfileResult

/** The saved download profiles (SET-02). */
interface ProfileStore {
    /** By name. */
    suspend fun list(): List<Profile>

    suspend fun get(id: ProfileId): Profile?

    suspend fun add(new: NewProfile): ProfileResult

    /** Saves [profile] under its id; [ProfileResult.NotFound] if it is gone, [ProfileResult.NameTaken] on a name clash. */
    suspend fun update(profile: Profile): ProfileResult

    /**
     * Removes the profile. The jobs that named it, and the default profile setting if it was this one, forget it in the
     * same transaction (the table has no foreign key to do it). Returns how many jobs named it.
     */
    suspend fun delete(id: ProfileId): Int
}
