import { HttpInterceptorFn } from '@angular/common/http';
import { environment } from '../environments/environment';

/**
 * Attaches the CSRF token to writes aimed at OUR api, and nothing else.
 *
 * WHY THIS IS NEEDED AT ALL
 * Angular already has XSRF support built in, and it works with the backend's
 * cookie name out of the box - but only for SAME-ORIGIN requests. It skips
 * absolute cross-origin URLs on purpose: quietly forwarding your token to
 * another site would hand that site the very thing CSRF protection exists to
 * keep secret.
 *
 * In development the page is served from :4200 and the API lives on :8080, so
 * every API call IS cross-origin and the built-in interceptor stayed silent.
 * The result was that sign-in failed with a confusing 401: Spring rejected the
 * tokenless POST, and because nobody was authenticated yet, that refusal was
 * reported as "you need to log in" rather than "your token was missing".
 *
 * So we forward the token ourselves, deliberately and narrowly:
 *   - only to environment.apiBaseUrl, never to an arbitrary host
 *   - only for state-changing methods; GET and HEAD never need it
 *
 * In production apiBaseUrl is empty, the API is same-origin, and Angular's own
 * interceptor would handle this anyway - this simply makes development behave
 * like production instead of failing in a way production never would.
 *
 * The alternative is a dev-server proxy (proxy.conf.json) that makes :8080
 * appear under :4200. That also works and removes CORS from development
 * entirely; it was not chosen here because it would hide the cross-origin
 * behaviour this project is meant to demonstrate.
 */
export const csrfInterceptor: HttpInterceptorFn = (req, next) => {
  const safeMethod = req.method === 'GET' || req.method === 'HEAD';
  const ourApi = req.url.startsWith(environment.apiBaseUrl || '/');

  if (safeMethod || !ourApi) {
    return next(req);
  }

  const token = readCookie('XSRF-TOKEN');
  if (!token) {
    // No token yet. Let the request through so the server's own refusal is what
    // surfaces, rather than inventing a client-side error that hides the cause.
    return next(req);
  }

  return next(req.clone({ setHeaders: { 'X-XSRF-TOKEN': token } }));
};

function readCookie(name: string): string | null {
  const match = document.cookie
    .split('; ')
    .find(row => row.startsWith(name + '='));
  return match ? decodeURIComponent(match.substring(name.length + 1)) : null;
}
