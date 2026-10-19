package dev.anydm.engine.store

import dev.anydm.engine.core.Format
import dev.anydm.engine.core.ProfileId
import dev.anydm.engine.files.NewProfile
import dev.anydm.engine.files.Profile
import dev.anydm.engine.files.ProfileResult
import dev.anydm.engine.store.db.AnydmDb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import dev.anydm.engine.store.db.Profile as ProfileRow

private val FORMAT_JSON =
    Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

internal class SqlProfileStore(
    private val database: AnydmDb,
    private val io: CoroutineDispatcher,
) : ProfileStore {
    private val queries = database.profileQueries

    // A write that was asked for is made, even if the caller is cancelled meanwhile (as for the other stores).
    private val writeContext = io + NonCancellable

    override suspend fun list(): List<Profile> = withContext(io) { queries.selectAll().executeAsList().mapNotNull { it.toProfile() } }

    override suspend fun get(id: ProfileId): Profile? = withContext(io) { queries.selectById(id).executeAsOneOrNull()?.toProfile() }

    override suspend fun add(new: NewProfile): ProfileResult =
        withContext(writeContext) {
            database.transactionWithResult {
                if (queries.selectByName(new.name).executeAsOneOrNull() != null) {
                    ProfileResult.NameTaken
                } else {
                    queries.insert(new.name, new.format?.let(::encode), new.categoryId, new.saveFolder, new.nameTemplate, new.speedLimit)
                    ProfileResult.Saved(checkNotNull(queries.selectLastInserted().executeAsOne().toProfile()))
                }
            }
        }

    override suspend fun update(profile: Profile): ProfileResult =
        withContext(writeContext) {
            database.transactionWithResult {
                when {
                    queries.selectById(profile.id).executeAsOneOrNull() == null -> {
                        ProfileResult.NotFound
                    }

                    queries.selectByName(profile.name).executeAsOneOrNull()?.let { it.id != profile.id } == true -> {
                        ProfileResult.NameTaken
                    }

                    else -> {
                        queries.update(
                            profile.name,
                            profile.format?.let(::encode),
                            profile.categoryId,
                            profile.saveFolder,
                            profile.nameTemplate,
                            profile.speedLimit,
                            profile.id,
                        )
                        ProfileResult.Saved(profile)
                    }
                }
            }
        }

    override suspend fun delete(id: ProfileId): Int =
        withContext(writeContext) {
            database.transactionWithResult {
                val used = queries.countJobs(id).executeAsOne().toInt()
                queries.clearJobs(id)
                queries.delete(id)
                val settings = database.settingQueries
                if (settings.selectAll().executeAsList().any { it.name == DEFAULT_PROFILE && it.data_ == id.value.toString() }) {
                    settings.remove(DEFAULT_PROFILE)
                }
                used
            }
        }

    private fun encode(format: Format): String = FORMAT_JSON.encodeToString(Format.serializer(), format)

    // A format this version can't read is no format: a profile must not stop the engine.
    private fun decode(text: String): Format? = runCatching { FORMAT_JSON.decodeFromString(Format.serializer(), text) }.getOrNull()

    // A row that breaks today's rules (a template this version rejects) is no profile: it must not stop a job, or the engine.
    private fun ProfileRow.toProfile(): Profile? =
        runCatching { Profile(id, name, format?.let(::decode), category_id, save_folder, name_template, speed_limit) }.getOrNull()
}
