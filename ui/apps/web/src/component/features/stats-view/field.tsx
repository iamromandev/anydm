import { formatBytes, formatSpeed } from "@/component/core/utils";
import { Chart, type ChartSpec } from "./chart";
import {
    RANGES,
    dayLabel,
    fillDays,
    hourLabel,
    speedIsPartial,
    speedSeries,
    topGroups,
    totalsOf,
    type RangeId,
    type StatsViewState,
    type TopItem,
} from "./state";
import "./field.css";

export interface StatsViewProps {
    state: StatsViewState;
    onRange: (id: RangeId) => void;
}

/** How many providers are named before the rest are folded into "Other". */
const TOP_PROVIDERS = 6;

const percent = (share: number) => `${Math.round(share * 100)}%`;

function TopList({ title, items }: { title: string; items: TopItem[] }) {
    return (
        <section className="stats-panel" aria-label={title}>
            <h3 className="stats-panel-title">{title}</h3>
            {items.length === 0 ? (
                <p className="stats-empty">Nothing moved in this range.</p>
            ) : (
                <ol className="stats-top">
                    {items.map((item) => (
                        <li key={item.key} className="stats-top-row">
                            <span className="stats-top-label">
                                {item.label}
                            </span>
                            <span className="stats-top-value">
                                {formatBytes(item.size)} · {percent(item.share)}
                            </span>
                            <span className="stats-meter" aria-hidden="true">
                                <span
                                    className="stats-meter-fill"
                                    style={{ width: percent(item.share) }}
                                />
                            </span>
                        </li>
                    ))}
                </ol>
            )}
        </section>
    );
}

export function StatsView({ state, onRange }: StatsViewProps) {
    const days = state.days;
    const totals = days ? totalsOf(days.groups) : null;
    const moved = totals
        ? totals.downloadedSize + totals.uploadedSize > 0
        : false;
    const daily = days ? fillDays(days.start, days.end, days.groups) : [];
    const speed = days ? speedSeries(days.speeds) : null;

    const volume: ChartSpec = {
        kind: "bar",
        unit: "bytes",
        labels: daily.map((d) => dayLabel(d.day)),
        datasets: [
            {
                label: "Downloaded",
                data: daily.map((d) => d.downloadedSize),
                color: "primary",
            },
            {
                label: "Uploaded",
                data: daily.map((d) => d.uploadedSize),
                color: "success",
            },
        ],
        description: totals
            ? `Daily volume: ${formatBytes(totals.downloadedSize)} downloaded and ${formatBytes(totals.uploadedSize)} uploaded over ${daily.length} days.`
            : "Daily volume",
    };
    const peak = speed && speed.maximum.length ? Math.max(...speed.maximum) : 0;
    const speedChart: ChartSpec = {
        kind: "line",
        unit: "bps",
        labels: speed ? speed.hours.map(hourLabel) : [],
        datasets: [
            {
                label: "Average",
                data: speed?.average ?? [],
                color: "primary",
            },
            {
                label: "Highest",
                data: speed?.maximum ?? [],
                color: "warning",
            },
        ],
        description: `Download speed by the hour, up to ${formatSpeed(peak)}.`,
    };

    return (
        <section className="stats-view" aria-label="Stats">
            <div className="stats-head">
                <h2 className="stats-title">Stats</h2>
                <div className="stats-range" role="group" aria-label="Range">
                    {RANGES.map((range) => (
                        <button
                            key={range.id}
                            type="button"
                            className={`stats-range-btn ${state.range === range.id ? "stats-range-btn--active" : ""}`}
                            aria-pressed={state.range === range.id}
                            disabled={state.loading}
                            onClick={() => onRange(range.id)}
                        >
                            {range.label}
                        </button>
                    ))}
                </div>
            </div>

            {state.failure && (
                <p className="stats-failure" role="alert">
                    {state.failure}
                </p>
            )}

            {days === null ? (
                !state.failure && <p className="stats-empty">Loading stats…</p>
            ) : (
                <div className="stats-body" aria-busy={state.loading}>
                    <ul className="stats-tiles">
                        <li className="stats-tile">
                            <span className="stats-tile-label">Downloaded</span>
                            <span className="stats-tile-value">
                                {formatBytes(totals!.downloadedSize)}
                            </span>
                        </li>
                        <li className="stats-tile">
                            <span className="stats-tile-label">Uploaded</span>
                            <span className="stats-tile-value">
                                {formatBytes(totals!.uploadedSize)}
                            </span>
                        </li>
                        <li className="stats-tile">
                            <span className="stats-tile-label">Finished</span>
                            <span className="stats-tile-value">
                                {totals!.finished}
                            </span>
                        </li>
                        <li className="stats-tile">
                            <span className="stats-tile-label">Failed</span>
                            <span className="stats-tile-value">
                                {totals!.failed}
                            </span>
                        </li>
                    </ul>

                    <section
                        className="stats-panel"
                        aria-label="Volume per day"
                    >
                        <h3 className="stats-panel-title">Volume per day</h3>
                        {moved ? (
                            <>
                                <Chart spec={volume} />
                                <table className="stats-sr-only">
                                    <caption>Volume per day</caption>
                                    <thead>
                                        <tr>
                                            <th scope="col">Day</th>
                                            <th scope="col">Downloaded</th>
                                            <th scope="col">Uploaded</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {daily.map((d) => (
                                            <tr key={d.day}>
                                                <th scope="row">{d.day}</th>
                                                <td>
                                                    {formatBytes(
                                                        d.downloadedSize,
                                                    )}
                                                </td>
                                                <td>
                                                    {formatBytes(
                                                        d.uploadedSize,
                                                    )}
                                                </td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </>
                        ) : (
                            <p className="stats-empty">
                                Nothing was downloaded or uploaded in this
                                range.
                            </p>
                        )}
                    </section>

                    <section className="stats-panel" aria-label="Speed">
                        <h3 className="stats-panel-title">Speed</h3>
                        {speed && speed.hours.length > 0 ? (
                            <Chart spec={speedChart} />
                        ) : (
                            <p className="stats-empty">
                                No downloads were running in this range.
                            </p>
                        )}
                        {speedIsPartial(state.range) && (
                            <p className="stats-note">
                                Speed is kept for 30 days, so older hours are
                                blank.
                            </p>
                        )}
                    </section>

                    <div className="stats-tops">
                        <TopList
                            title="Top providers"
                            items={topGroups(
                                state.providers?.groups ?? [],
                                "provider",
                                TOP_PROVIDERS,
                            )}
                        />
                        <TopList
                            title="By kind"
                            items={topGroups(
                                state.kinds?.groups ?? [],
                                "kind",
                                3,
                            )}
                        />
                    </div>
                </div>
            )}
        </section>
    );
}
