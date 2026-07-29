/**
 * PRODUCTION environment (the default).
 *
 * `npm run build` uses this file. `npm start` swaps in
 * environment.development.ts instead, via the fileReplacements entry in
 * angular.json.
 *
 * apiBaseUrl is EMPTY on purpose. An empty base makes every request relative -
 * "/api/assignments" rather than "http://somewhere:8080/api/assignments" - so
 * the built site calls whatever host is serving it. That is the normal
 * production shape: the site and the API sit behind one address, usually a
 * reverse proxy that forwards /api to the backend.
 *
 * It also means production needs no CORS permission at all, because the browser
 * never sees a second origin. CORS is a development concern here, created by
 * serving the page on :4200 and the API on :8080.
 *
 * To point a build at an API on a different host, set an absolute URL here
 * with no trailing slash - for example 'https://api.example.com'.
 */
export const environment = {
  production: true,
  apiBaseUrl: ''
};
