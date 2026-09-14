import { component$ } from "@qwik.dev/core";
import { segmentLayout, type SegmentView } from "@/lib/api";
import "./field.css";

interface SegmentBarProps {
    segments: SegmentView[];
    label: string;
}

/**
 * The file, left to right, one slice per connection.
 *
 * Slice widths are proportional to each segment's byte range rather than equal,
 * so the strip stays a map of the file: a slice that is falling behind is
 * visibly a region of the file that is falling behind.
 */
export const SegmentBar = component$<SegmentBarProps>(({ segments, label }) => {
    const layout = segmentLayout(segments);
    if (layout.length === 0) return null;

    return (
        <div class="segment-bar" role="group" aria-label={`${label} segments`}>
            {layout.map((slice) => (
                <div
                    key={slice.index}
                    class="segment-slice"
                    style={{ width: `${slice.widthPercent}%` }}
                    title={`Segment ${slice.index + 1}: ${slice.fillPercent.toFixed(0)}%`}
                >
                    <div
                        class="segment-slice-fill"
                        style={{ width: `${slice.fillPercent}%` }}
                    />
                </div>
            ))}
        </div>
    );
});
