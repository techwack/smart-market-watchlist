import { Component } from 'react'

/**
 * Contains a render failure to the row that caused it.
 *
 * Added after a single bad sparkline (an off-by-one on the split index)
 * threw and took the entire page down to a blank screen. One row failing to
 * draw is a defect; every row disappearing because of it is a much worse
 * one, and the brief is explicit that the thing has to actually work.
 *
 * Deliberately minimal: it reports that this row couldn't render and gets
 * out of the way, rather than pretending nothing happened.
 */
export default class ErrorBoundary extends Component {
  constructor(props) {
    super(props)
    this.state = { failed: false }
  }

  static getDerivedStateFromError() {
    return { failed: true }
  }

  componentDidCatch(error, info) {
    console.error('Render failed in', this.props.label ?? 'component', error, info)
  }

  render() {
    if (this.state.failed) {
      return <span className="muted small-text">{this.props.fallback ?? 'Could not display'}</span>
    }
    return this.props.children
  }
}
