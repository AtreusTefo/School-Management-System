/**
 * DEVELOPMENT environment.
 *
 * `npm start` uses this file: angular.json's "development" configuration
 * replaces environment.ts with this one, and serve defaults to that
 * configuration.
 *
 * Here the two halves genuinely are on different origins - the page on :4200,
 * the API on :8080 - so requests must name the API's host in full, and the
 * backend must grant CORS permission for :4200. Both of those are development
 * facts, not properties of the application.
 *
 * Change this value to point the dev server at a backend on another port or
 * machine; no source file outside src/environments/ needs to know.
 */
export const environment = {
  production: false,
  apiBaseUrl: 'http://localhost:8080'
};
