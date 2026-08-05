import { bootstrapApplication } from '@angular/platform-browser';
import { AppComponent } from './app/app.component';
import { appConfig } from './app/app.config';

/**
 * Bootstraps the standalone Angular app.
 *
 * Providers now live in app.config.ts rather than inline here, because there
 * is more than one of them worth naming on its own - provideRouter(routes) is
 * what turns the four page components in app.routes.ts into actual
 * navigation, alongside the HTTP client and its CSRF interceptor that were
 * already here.
 */
bootstrapApplication(AppComponent, appConfig)
  .catch((err) => console.error(err));
