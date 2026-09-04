/**
 * Delta Sparkline.
 *
 * A normal watchlist draws a price line and leaves you to work out what's
 * new. This one splits the same line at the moment you last acknowledged the
 * row: everything before that is muted grey history you've already seen, and
 * everything after is drawn thick in green or red — the part you haven't.
 *
 * So "what changed since I last checked" is answered by the shape itself,
 * before reading a single number. A marker sits on the split point, and the
 * ack price is carried across as a dashed baseline so the size of the move
 * is readable against it.
 *
 * Rendered as inline SVG rather than a charting library: it's ~60 lines,
 * has no dependency, and a library's default styling would have to be
 * fought to get this two-tone behaviour anyway.
 */
export default function DeltaSparkline({
  history,
  lastAcknowledgedAt,
  width = 148,
  height = 40,
}) {
  if (!history || history.length < 2) {
    return <div className="sparkline-empty" style={{ width, height }} />
  }

  const padding = 3
  const prices = history.map((p) => p.price)
  const min = Math.min(...prices)
  const max = Math.max(...prices)
  const span = max - min || 1

  const x = (i) => padding + (i / (history.length - 1)) * (width - padding * 2)
  const y = (price) => padding + (1 - (price - min) / span) * (height - padding * 2)

  // Where to cut the line: the first sample taken at or after the moment the
  // user acknowledged the row.
  //
  // Three cases, and the middle one is easy to miss: acknowledging happens
  // between ticks, so for a few seconds there is no sample newer than the
  // acknowledgement and findIndex returns -1. Treating that as index -1
  // crashes on history[-1]; treating it as 0 would wrongly paint the whole
  // line as unseen. The truthful answer is that everything so far has been
  // seen and nothing new has arrived yet.
  const ackTime = lastAcknowledgedAt ? new Date(lastAcknowledgedAt).getTime() : null
  let splitIndex
  if (!ackTime) {
    splitIndex = 0 // never acknowledged: the whole line is new
  } else {
    const firstUnseen = history.findIndex((p) => new Date(p.at).getTime() >= ackTime)
    splitIndex = firstUnseen === -1 ? history.length - 1 : firstUnseen
  }

  const hasSeenPortion = splitIndex > 0
  const seen = hasSeenPortion ? history.slice(0, splitIndex + 1) : []
  const unseen = history.slice(hasSeenPortion ? splitIndex : 0)

  const toPath = (points, offset) =>
    points
      .map((p, i) => `${i === 0 ? 'M' : 'L'} ${x(i + offset).toFixed(1)} ${y(p.price).toFixed(1)}`)
      .join(' ')

  // Direction of the *unseen* portion — that's what the colour reports on.
  // A single unseen point (just acknowledged) is neither up nor down.
  const first = unseen[0]?.price ?? 0
  const last = unseen[unseen.length - 1]?.price ?? 0
  const direction = unseen.length < 2 ? 'flat' : last > first ? 'up' : last < first ? 'down' : 'flat'

  const splitX = x(splitIndex)
  const splitY = y(history[splitIndex].price)

  return (
    <svg
      className="sparkline"
      width={width}
      height={height}
      viewBox={`0 0 ${width} ${height}`}
      role="img"
      aria-label={
        hasSeenPortion
          ? `Price history; the highlighted section is movement since you last looked, trending ${direction}`
          : 'Price history since you started tracking this'
      }
    >
      {/* Where the price stood when you last looked, carried across. */}
      {hasSeenPortion && (
        <line
          className="sparkline-baseline"
          x1={splitX}
          y1={splitY}
          x2={width - padding}
          y2={splitY}
        />
      )}

      {/* Already seen: muted, deliberately recessive. */}
      {hasSeenPortion && (
        <path className="sparkline-seen" d={toPath(seen, 0)} fill="none" />
      )}

      {/* Since you last looked: the point of the whole component. */}
      <path
        className={`sparkline-unseen sparkline-${direction}`}
        d={toPath(unseen, hasSeenPortion ? splitIndex : 0)}
        fill="none"
      />

      {/* The moment you last looked. */}
      {hasSeenPortion && <circle className="sparkline-marker" cx={splitX} cy={splitY} r="2.5" />}
    </svg>
  )
}
