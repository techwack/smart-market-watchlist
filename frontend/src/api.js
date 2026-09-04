const BASE = 'http://localhost:8080'
const VIEWER_KEY = 'watchlist.viewerId'

/**
 * Viewer identity persisted in localStorage — this is what makes "close the
 * app, come back later, see what changed" work without a login. Same browser
 * = same viewer = server-side ViewState survives the session.
 *
 * Known limitation, stated rather than hidden: identity is per-browser, so
 * "across devices" holds only if the same id is carried over. Real auth is
 * the fix; the backend already treats viewerId as an opaque string, so that
 * swap touches this file and the controller's header resolution, nothing else.
 */
export function getViewerId() {
  let id = localStorage.getItem(VIEWER_KEY)
  if (!id) {
    id = crypto.randomUUID()
    localStorage.setItem(VIEWER_KEY, id)
  }
  return id
}

/** Lets the user paste an existing id to sync a second device to one watchlist. */
export function setViewerId(id) {
  localStorage.setItem(VIEWER_KEY, id.trim())
}

async function request(path, options = {}) {
  const response = await fetch(`${BASE}${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      'X-Viewer-Id': getViewerId(),
      ...(options.headers || {}),
    },
  })
  if (!response.ok) {
    const body = await response.json().catch(() => ({}))
    throw new Error(body.error || `Request failed: ${response.status}`)
  }
  return response.status === 204 ? null : response.json()
}

export const api = {
  getWatchlist: () => request('/watchlist'),
  getInstruments: () => request('/watchlist/instruments'),
  add: (symbol, costBasis) =>
    request('/watchlist', { method: 'POST', body: JSON.stringify({ symbol, costBasis }) }),
  remove: (symbol) => request(`/watchlist/${symbol}`, { method: 'DELETE' }),
  acknowledge: (symbol) => request(`/watchlist/${symbol}/ack`, { method: 'POST' }),
  /** Demo control for showing the degraded path live. */
  setFeedOutage: (enabled) =>
    request(`/watchlist/debug/feed-outage?enabled=${enabled}`, { method: 'POST' }),
}
