import { Link } from 'react-router';
export function Forbidden() {
  return <section className="state route-state" role="alert"><p className="eyebrow">403</p><h1>Access denied</h1>
    <p>You are signed in, but you don't have permission to access this page.</p>
    <Link className="text-link" to="/">Back to Overview</Link></section>;
}
