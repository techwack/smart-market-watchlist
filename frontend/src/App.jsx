import { useCallback, useEffect, useMemo, useState } from 'react'
import { api, getViewerId, setViewerId } from './api.js'
import DeltaSparkline from './DeltaSparkline.jsx'
import ErrorBoundary from './ErrorBoundary.jsx'

const POLL_MS = 3000

export default function App() {
  const [view, setView] = useState({ rows: [], feedHealthy: true, lastSuccessfulFetch: null })
  const [instruments, setInstruments] = useState([])
  const [error, setError] = useState(null)
  const [reachable, setReachable] = useState(true)
  const [symbol, setSymbol] = useState('')
  const [costBasis, setCostBasis] = useState('')

  const load = useCallback(async () => {
    try {
      setView(await api.getWatchlist())
      setReachable(true)
    } catch {
      // Our own backend is unreachable. Keep the last rows on screen instead
      // of blanking it, but stop claiming the prices are live.
      setReachable(false)
    }
  }, [])

  useEffect(() => {
    load()
    api.getInstruments().then(setInstruments).catch(() => {})
    const timer = setInterval(load, POLL_MS)
    return () => clearInterval(timer)
  }, [load])

  const { rows, feedHealthy, lastSuccessfulFetch } = view
  const attention = useMemo(() => rows.filter((r) => r.needsAttention), [rows])
  const quiet = useMemo(() => rows.filter((r) => !r.needsAttention), [rows])
  const degraded = !reachable || !feedHealthy

  const handleAdd = async () => {
    if (!symbol) return
    try {
      await api.add(symbol, costBasis ? Number(costBasis) : null)
      setSymbol('')
      setCostBasis('')
      setError(null)
      await load()
    } catch (e) {
      setError(e.message)
    }
  }

  const handleAck = async (s) => {
    try {
      await api.acknowledge(s)
      await load()
    } catch (e) {
      setError(e.message)
    }
  }

  return (
    <div className="app">
      <header className="header">
        <div>
          <h1>Smart Market Watchlist</h1>
          <p className="subtitle">What has meaningfully changed since you last looked</p>
        </div>
        <span className={degraded ? 'status status-down' : 'status status-live'}>
          {degraded ? 'Delayed' : 'Live'}
        </span>
      </header>

      {degraded && (
        <div className="banner">
          <strong>Data currently delayed.</strong>{' '}
          {reachable
            ? 'The market feed is unreachable, so these are the last known prices.'
            : 'Cannot reach the server, so these are the last prices we received.'}
          {lastSuccessfulFetch && ` Last updated ${timeAgo(lastSuccessfulFetch)}.`}
        </div>
      )}

      <section className="controls">
        <select value={symbol} onChange={(e) => setSymbol(e.target.value)}>
          <option value="">Add a symbol...</option>
          {instruments
            .filter((s) => !rows.some((r) => r.symbol === s))
            .map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
        </select>
        <input
          type="number"
          placeholder="Your avg cost (optional)"
          value={costBasis}
          onChange={(e) => setCostBasis(e.target.value)}
        />
        <button onClick={handleAdd} disabled={!symbol}>
          Add
        </button>
        {attention.length > 0 && (
          <button
            className="secondary"
            onClick={async () => {
              await Promise.all(attention.map((r) => api.acknowledge(r.symbol)))
              await load()
            }}
          >
            Mark all as seen
          </button>
        )}
      </section>

      {error && <p className="error">{error}</p>}

      {attention.length > 0 && (
        <section>
          <h2 className="section-title attention">Needs your attention ({attention.length})</h2>
          <div className="rows">
            {attention.map((row) => (
              <Row key={row.symbol} row={row} onAck={handleAck} onRemove={remove(load)} highlight />
            ))}
          </div>
        </section>
      )}

      <section>
        <h2 className="section-title">
          {attention.length > 0 ? 'Nothing notable here' : 'Your watchlist'}
        </h2>
        {rows.length === 0 && (
          <p className="empty">Nothing tracked yet. Add a symbol above to start watching it.</p>
        )}
        <div className="rows">
          {quiet.map((row) => (
            <Row key={row.symbol} row={row} onAck={handleAck} onRemove={remove(load)} />
          ))}
        </div>
      </section>

      <ViewerIdPanel />
    </div>
  )
}

const remove = (reload) => async (symbol) => {
  await api.remove(symbol)
  await reload()
}

function Row({ row, onAck, onRemove, highlight }) {
  const direction = (v) => (v > 0 ? 'up' : v < 0 ? 'down' : 'flat')

  return (
    <div className={`row ${highlight ? 'row-highlight' : ''}`}>
      <div className="row-main">
        <div className="row-symbol">
          <span className="symbol">{row.symbol}</span>
          {row.stale && <span className="badge badge-stale">Delayed</span>}
        </div>
        <div className="row-price">
          <span className="price">₹{row.price.toFixed(2)}</span>
          <span className={`change ${direction(row.changePctToday)}`}>
            {row.changePctToday >= 0 ? '+' : ''}
            {row.changePctToday.toFixed(2)}% today
          </span>
          {row.costBasis && (
            <span className="muted small-text">Your cost ₹{row.costBasis.toFixed(2)}</span>
          )}
        </div>

        <div className="row-spark">
          <ErrorBoundary label={`sparkline for ${row.symbol}`} fallback="chart unavailable">
            <DeltaSparkline
              history={row.history}
              lastAcknowledgedAt={row.lastAcknowledgedAt}
            />
          </ErrorBoundary>
          <span className="spark-caption muted">
            {row.lastAcknowledgedAt ? 'since you last looked' : 'tracking from now'}
          </span>
        </div>
        <div className="row-actions">
          {row.needsAttention && (
            <button className="small" onClick={() => onAck(row.symbol)}>
              Mark seen
            </button>
          )}
          <button className="small ghost" onClick={() => onRemove(row.symbol)}>
            Remove
          </button>
        </div>
      </div>

      {row.signals.length > 0 ? (
        <ul className="signals">
          {row.signals.map((signal) => (
            <li key={signal.type} className={`signal signal-${signal.severity.toLowerCase()}`}>
              {signal.message}
            </li>
          ))}
        </ul>
      ) : (
        <p className="signals-empty muted">
          {row.lastAcknowledgedAt
            ? `No meaningful change since you looked ${timeAgo(row.lastAcknowledgedAt)}`
            : 'Tracking from now on'}
        </p>
      )}
    </div>
  )
}

/**
 * Makes the cross-device story concrete and demoable: paste this id into
 * another browser and the same watchlist and acknowledgement state follow.
 * Stands in for the account system this deliberately doesn't build.
 */
function ViewerIdPanel() {
  const [value, setValue] = useState(getViewerId())

  return (
    <footer className="viewer-panel">
      <label htmlFor="viewer">Viewer ID — paste on another device to sync this watchlist</label>
      <div className="viewer-row">
        <input id="viewer" value={value} onChange={(e) => setValue(e.target.value)} spellCheck={false} />
        <button
          className="small"
          onClick={() => {
            setViewerId(value)
            window.location.reload()
          }}
        >
          Switch
        </button>
      </div>
    </footer>
  )
}

function timeAgo(iso) {
  const seconds = Math.floor((Date.now() - new Date(iso).getTime()) / 1000)
  if (seconds < 60) return `${seconds}s ago`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}m ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}h ago`
  return `${Math.floor(hours / 24)}d ago`
}
