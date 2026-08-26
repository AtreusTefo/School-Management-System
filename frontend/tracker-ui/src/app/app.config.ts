import { ApplicationConfig } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter, withInMemoryScrolling } from '@angular/router';
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

    /*
     * anchorScrolling IS NOT ON BY DEFAULT, and without it a routerLink
     * carrying a fragment is a broken link that still looks like a working
     * one: the URL gains "#signin", nothing moves, and the reader is left
     * staring at the same screen wondering whether the button did anything.
     * The landing page's two "Sign in" buttons and its "See what it does"
     * link all depend on this.
     *
     * scrollPositionRestoration: 'enabled' is the companion fix - without it
     * Angular keeps the previous page's scroll offset across a navigation, so
     * following a footer link from halfway down the landing page opens the
     * privacy policy already scrolled into its middle.
     */
    provideRouter(routes, withInMemoryScrolling({
      anchorScrolling: 'enabled',
      scrollPositionRestoration: 'enabled'
    }))
  ]
};
