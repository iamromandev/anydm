import { useMemo } from "react";
import { AppShell } from "@/component/layouts/app-shell";
import { useApp } from "@/hook/use-app";
import { selectPlayerNav } from "@/state/player";

/** What "Clear finished" asks before it sweeps. Fixed text, so one object. */
const CLEAR_FINISHED_PROMPT = {
    heading: "Clear finished downloads?",
    body: "Completed downloads leave the list and keep their files. Anything that failed is discarded along with whatever it had downloaded.",
    confirmLabel: "Clear finished",
};

/**
 * The page: the app's state, handed to the shell. Every handler is a method of
 * `app`, made once, so the cards below can skip redrawing when their own row
 * has not changed.
 */
export function HomePage() {
    const { state, app } = useApp();
    const nav = selectPlayerNav(state);

    // Handlers that are not a method of `app` as they stand, kept stable.
    const bound = useMemo(
        () => ({
            pauseVideo: (id: string) => app.entries.videoAction(id, "pause"),
            resumeVideo: (id: string) => app.entries.videoAction(id, "resume"),
            nextItem: () => app.player.step(1),
            previousItem: () => app.player.step(-1),
        }),
        [
            app,
        ],
    );

    return (
        <AppShell
            tasks={state.tasks}
            filter={state.filter}
            searchQuery={state.searchQuery}
            sort={state.sort}
            onSortChange={app.settings.changeSort}
            settingsOpen={state.settingsOpen}
            onSettingsOpen={app.settings.open}
            onSettingsClose={app.settings.close}
            prefs={state.prefs}
            onPrefsChange={app.settings.changePrefs}
            serverSettings={state.serverSettings}
            apiKey={state.apiKey}
            apiKeyMessage={state.apiKeyMessage}
            onApiKeySave={app.settings.saveApiKey}
            now={state.now}
            connection={state.connection}
            disk={state.disk}
            summary={state.summary}
            page={state.page}
            totalPages={state.totalPages}
            loadingMore={state.loadingMore}
            onLoadMore={app.tasks.loadMore}
            toasts={state.toasts}
            onDismissToast={app.toasts.dismiss}
            sidebarOpen={state.sidebarOpen}
            sidebarCollapsed={state.sidebarCollapsed}
            onSidebarToggle={app.settings.toggleSidebar}
            onSidebarCollapseToggle={app.settings.toggleSidebarCollapse}
            onFilterChange={app.tasks.changeFilter}
            categories={state.categories}
            category={state.category}
            addCategory={state.addCategory}
            onCategoryChange={app.tasks.changeCategory}
            onAddCategoryChange={app.categories.changeAdd}
            onMoveCategory={app.tasks.moveToCategory}
            onCategoryCreate={app.categories.create}
            onCategoryUpdate={app.categories.update}
            onCategoryOrder={app.categories.order}
            onCategoryDelete={app.categories.remove}
            onSearchChange={app.tasks.changeSearch}
            addModalOpen={state.addModalOpen}
            onAddModalClose={app.add.close}
            onAddClick={app.add.click}
            addInitial={state.addInitial}
            onAddFound={app.add.found}
            onAdd={app.add.add}
            onOpenDownload={app.add.openDownload}
            onResolve={app.add.resolve}
            onAddPlaylist={app.add.addPlaylist}
            onBatchAdded={app.tasks.loadSummary}
            highlightId={state.highlightId}
            playerModalOpen={state.playerModalOpen}
            playerUrl={state.playerUrl}
            playerKind={state.playerKind}
            playerTaskId={state.playerTaskId}
            playerFileIndex={state.playerFileIndex}
            playerFiles={state.playerFiles}
            playerFromTorrent={state.playerFromTorrent}
            playerPositions={state.playerPositions}
            onPositionSaved={app.player.positionSaved}
            onPlayClick={app.player.playClick}
            onPlayerModalClose={app.player.close}
            onPlay={app.player.playTask}
            onPlayQueue={app.player.playQueue}
            onPlayGroup={app.player.playGroup}
            playerHasPreviousItem={nav.hasPrevious}
            playerHasNextItem={nav.hasNext}
            playerNextItemTitle={nav.nextTitle}
            onNextItem={bound.nextItem}
            onPreviousItem={bound.previousItem}
            onPause={app.actions.pause}
            onResume={app.actions.resume}
            onDownloadFile={app.actions.downloadFile}
            onRemove={app.actions.remove}
            removing={state.removing}
            onRemoveCancel={app.actions.removeCancel}
            onRemoveConfirm={app.actions.removeConfirm}
            onBulk={app.actions.bulk}
            bulkPrompt={
                state.pendingBulk === "clear_finished"
                    ? CLEAR_FINISHED_PROMPT
                    : null
            }
            onBulkCancel={app.actions.bulkCancel}
            onBulkConfirm={app.actions.bulkConfirm}
            onStopSeeding={app.actions.stopSeeding}
            entries={state.entries}
            onToggleEntries={app.entries.toggle}
            onLoadMoreEntries={app.entries.load}
            onPauseVideo={bound.pauseVideo}
            onResumeVideo={bound.resumeVideo}
            onRemoveVideo={app.entries.removeVideo}
            searchEnabled={state.searchEnabled}
            searchTorrents={state.searchTorrents}
            searchYoutube={state.searchYoutube}
            onSourcesChanged={app.settings.refreshSearchAvailability}
            onNotify={app.toasts.notify}
        />
    );
}
