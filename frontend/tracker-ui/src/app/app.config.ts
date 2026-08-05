import { ApplicationConfig } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';
import { csrfInterceptor } from './csrf.interceptor';

/**
 * The application's providers, kept separate from main.ts so they can be
 * imported by anything that needs to bootstrap the same configuration - a
 * future test harness, for one, which is the usual reason Angular projects
 * split this out even for an app with a single entry point.
 */
export const appConfig: ApplicationConfig = {
  providers: [
    provideHttpClient(withInterceptors([csrfInterceptor])),
    provideRouter(routes)
  ]
};
