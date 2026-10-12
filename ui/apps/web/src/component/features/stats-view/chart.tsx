import { useEffect, useRef } from "react";
import { formatBytes, formatSpeed } from "@/component/core/utils";

/** What a chart draws, as plain data: the view decides it, this only paints it. */
export type ChartSpec = {
    kind: "bar" | "line";
    labels: string[];
    /** How a value reads on the axis and in the tooltip. */
    unit: "bytes" | "bps";
    datasets: Array<{
        label: string;
        data: number[];
        /** A theme colour, by the name of its `--color-*` variable. */
        color: "primary" | "success" | "warning";
    }>;
    /** What a person who cannot see the chart is told it shows. */
    description: string;
};

export interface ChartProps {
    spec: ChartSpec;
}

/**
 * One Chart.js chart on a canvas.
 *
 * Chart.js is loaded when a chart first appears, not with the page, and
 * the chart is drawn again when the theme changes because its colours are read
 * from the theme's CSS variables at that moment.
 */
export function Chart({ spec }: ChartProps) {
    const canvas = useRef<HTMLCanvasElement>(null);
    // The view builds a fresh spec on every render, and renders on every
    // stream frame; what the chart depends on is what the spec says.
    const specKey = JSON.stringify(spec);

    // Starts as soon as the chart is on the page: the view only mounts it once
    // it is opened, so nothing here loads Chart.js for a person who never does.
    useEffect(() => {
        const element = canvas.current;
        if (!element) return;
        const current = JSON.parse(specKey) as ChartSpec;
        let cancelled = false;
        let stop: (() => void) | undefined;

        void (async () => {
            const {
                Chart: ChartJs,
                BarController,
                BarElement,
                CategoryScale,
                Legend,
                LinearScale,
                LineController,
                LineElement,
                PointElement,
                Tooltip,
            } = await import("chart.js");
            // Gone while Chart.js was loading: the page moved on.
            if (cancelled) return;
            ChartJs.register(
                BarController,
                BarElement,
                CategoryScale,
                Legend,
                LinearScale,
                LineController,
                LineElement,
                PointElement,
                Tooltip,
            );

            const format = current.unit === "bytes" ? formatBytes : formatSpeed;
            let chart: InstanceType<typeof ChartJs> | undefined;

            const draw = () => {
                chart?.destroy();
                const style = getComputedStyle(document.documentElement);
                const colour = (name: string) =>
                    style.getPropertyValue(`--color-${name}`).trim();
                const text = colour("foreground");
                const grid = colour("border");
                chart = new ChartJs(element, {
                    type: current.kind,
                    data: {
                        labels: current.labels,
                        datasets: current.datasets.map((set) => ({
                            label: set.label,
                            data: set.data,
                            backgroundColor: colour(set.color),
                            borderColor: colour(set.color),
                            borderWidth: current.kind === "line" ? 2 : 0,
                            pointRadius: 0,
                            pointHoverRadius: 4,
                            tension: 0.25,
                        })),
                    },
                    options: {
                        responsive: true,
                        maintainAspectRatio: false,
                        animation: false,
                        interaction: { mode: "index", intersect: false },
                        plugins: {
                            legend: { labels: { color: text, boxWidth: 12 } },
                            tooltip: {
                                callbacks: {
                                    label: (item) =>
                                        `${item.dataset.label}: ${format(Number(item.parsed.y))}`,
                                },
                            },
                        },
                        scales: {
                            x: {
                                ticks: {
                                    color: text,
                                    maxRotation: 0,
                                    autoSkip: true,
                                    maxTicksLimit: 8,
                                },
                                grid: { display: false },
                            },
                            y: {
                                beginAtZero: true,
                                ticks: {
                                    color: text,
                                    callback: (value) => format(Number(value)),
                                },
                                grid: { color: grid },
                            },
                        },
                    },
                });
            };

            draw();
            // The theme is a class on <html> or the system's scheme; either redraws.
            const observer = new MutationObserver(draw);
            observer.observe(document.documentElement, {
                attributes: true,
                attributeFilter: [
                    "class",
                    "data-theme",
                ],
            });
            const scheme = matchMedia("(prefers-color-scheme: dark)");
            scheme.addEventListener("change", draw);
            stop = () => {
                observer.disconnect();
                scheme.removeEventListener("change", draw);
                chart?.destroy();
            };
        })();

        return () => {
            cancelled = true;
            stop?.();
        };
    }, [
        specKey,
    ]);

    return (
        <div className="stats-chart">
            <canvas ref={canvas} role="img" aria-label={spec.description} />
        </div>
    );
}
